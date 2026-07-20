package lv.jolkins.pixelorchestrator.app.ticket

internal data class TicketControlCodeFastStateSnapshot(
  val streamActive: Boolean,
  val rootHardwareCaptureActive: Boolean,
  val hardwareCaptureVerified: Boolean,
  val sessionLive: Boolean,
  val controlCodeModeActive: Boolean,
  val lastFrameSentAtMillis: Long,
  val streamEpoch: Long,
  val frameSequence: Long,
  val lastReadyRevision: String,
  val lastReadyAtMillis: Long
)

internal data class TicketControlCodeAcceptedRevision(
  val revision: String,
  val acceptedAtMillis: Long,
  val reason: String,
  val readyStreamEpoch: Long,
  val revisionStreamEpoch: Long,
  val revisionFrameSequence: Long,
  val epochMatchesCurrentStream: Boolean,
  val frameAgeMillis: Long
)

internal data class TicketControlCodeFastStateDecision(
  val ready: Boolean,
  val reason: String,
  val acceptedRevision: TicketControlCodeAcceptedRevision? = null
)

/**
 * Pure decision boundary for deciding whether a browser-provided fast-state revision can skip
 * control-code warm-up. The service remains responsible only for reading and applying runtime state.
 */
internal object TicketControlCodeFastStatePolicy {
  fun evaluate(
    expectedRevision: String,
    nowMillis: Long,
    readyTtlMillis: Long,
    liveFrameMaxAgeMillis: Long,
    snapshot: TicketControlCodeFastStateSnapshot
  ): TicketControlCodeFastStateDecision {
    val revision = expectedRevision.trim()
    if (revision.isBlank()) {
      return rejected("revision_missing")
    }

    val localRevisionFresh = revision == snapshot.lastReadyRevision &&
      snapshot.lastReadyAtMillis > 0L &&
      elapsedMillis(snapshot.lastReadyAtMillis, nowMillis) <= readyTtlMillis
    if (localRevisionFresh) {
      return if (baseReady(snapshot)) {
        TicketControlCodeFastStateDecision(ready = true, reason = "local_revision_fresh")
      } else {
        rejected("local_revision_runtime_not_ready")
      }
    }

    if (!snapshot.streamActive ||
      !snapshot.rootHardwareCaptureActive ||
      !snapshot.hardwareCaptureVerified
    ) {
      return rejected("capture_not_ready")
    }
    if (!snapshot.sessionLive || snapshot.controlCodeModeActive) {
      return rejected("session_not_ready")
    }

    if (snapshot.lastFrameSentAtMillis <= 0L) {
      return rejected("live_frame_missing")
    }
    val frameAgeMillis = elapsedMillis(snapshot.lastFrameSentAtMillis, nowMillis)
    if (frameAgeMillis > liveFrameMaxAgeMillis) {
      return rejected("live_frame_stale")
    }

    val revisionParts = revision.split(":")
    if (revisionParts.size < 4) {
      return rejected("revision_malformed")
    }
    val revisionStreamEpoch = revisionParts[2].toLongOrNull()
      ?: return rejected("revision_epoch_invalid")
    val revisionFrameSequence = revisionParts[3].toLongOrNull()
      ?: return rejected("revision_sequence_invalid")
    val epochMatchesCurrentStream = revisionStreamEpoch == snapshot.streamEpoch
    if (epochMatchesCurrentStream && revisionFrameSequence > snapshot.frameSequence) {
      return rejected("revision_ahead_of_live_frame")
    }

    val accepted = TicketControlCodeAcceptedRevision(
      revision = revision,
      acceptedAtMillis = nowMillis,
      reason = if (epochMatchesCurrentStream) {
        "server_fast_revision_live_stream"
      } else {
        "server_fast_revision_fresh_stream"
      },
      readyStreamEpoch = if (epochMatchesCurrentStream) revisionStreamEpoch else snapshot.streamEpoch,
      revisionStreamEpoch = revisionStreamEpoch,
      revisionFrameSequence = revisionFrameSequence,
      epochMatchesCurrentStream = epochMatchesCurrentStream,
      frameAgeMillis = frameAgeMillis
    )
    return TicketControlCodeFastStateDecision(
      ready = true,
      reason = "server_revision_accepted",
      acceptedRevision = accepted
    )
  }

  private fun baseReady(snapshot: TicketControlCodeFastStateSnapshot): Boolean {
    return snapshot.streamActive &&
      snapshot.hardwareCaptureVerified &&
      snapshot.sessionLive &&
      !snapshot.controlCodeModeActive
  }

  private fun elapsedMillis(timestampMillis: Long, nowMillis: Long): Long {
    return (nowMillis - timestampMillis).coerceAtLeast(0L)
  }

  private fun rejected(reason: String): TicketControlCodeFastStateDecision {
    return TicketControlCodeFastStateDecision(ready = false, reason = reason)
  }
}
