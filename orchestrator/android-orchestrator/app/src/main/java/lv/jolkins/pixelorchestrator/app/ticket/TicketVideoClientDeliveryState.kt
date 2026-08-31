package lv.jolkins.pixelorchestrator.app.ticket

internal data class TicketVideoDeliveryFrame(
  val bytes: ByteArray,
  val keyFrame: Boolean,
  val epoch: Long,
  val sequence: Long
)

internal data class TicketVideoDeliveryDecision(
  val frameToWrite: TicketVideoDeliveryFrame? = null,
  val writeToken: Long = 0L,
  val droppedFrames: Int = 0,
  val requestImmediateRefresh: Boolean = false,
  val closeSlowClient: Boolean = false,
  val dropReason: String? = null,
  val blockedMillis: Long = 0L
)

internal data class TicketVideoDeliverySnapshot(
  val expectedEpoch: Long,
  val configReady: Boolean,
  val writeInFlight: Boolean,
  val inFlightSequence: Long,
  val inFlightWriteToken: Long,
  val pendingFrames: Int,
  val pendingBytes: Int,
  val pendingSequence: Long,
  val latestAcceptedSequence: Long,
  val closed: Boolean
)

/**
 * Per-viewer delivery state for the fixed all-intra Ticket stream.
 *
 * A client has one write in flight and, at most, the newest pending independent frame. Replacing
 * an older pending frame prevents a slow viewer from building latency while the generation and
 * write-token guards keep obsolete asynchronous work from affecting a replacement connection.
 */
internal class TicketVideoClientDeliveryState(
  internal val expectedEpoch: Long,
  private val maxFrameBytes: Int,
  private val slowCloseMillis: Long
) {
  private var pendingFrame: TicketVideoDeliveryFrame? = null
  private var configReady = false
  private var inFlightSinceMillis = 0L
  private var inFlightSequence = 0L
  private var inFlightWriteToken = 0L
  private var nextWriteToken = 0L
  private var latestAcceptedSequence = 0L
  private var closed = false

  init {
    require(expectedEpoch >= 0L)
    require(maxFrameBytes > 0)
    require(slowCloseMillis > 0L)
  }

  @Synchronized
  fun markConfigReady(): Boolean {
    if (closed || expectedEpoch <= 0L) return false
    configReady = true
    return true
  }

  @Synchronized
  fun canAcceptConfig(): Boolean {
    return !closed && !configReady && expectedEpoch > 0L
  }

  @Synchronized
  fun canWrite(writeToken: Long): Boolean {
    return !closed && writeToken > 0L && inFlightWriteToken == writeToken
  }

  @Synchronized
  fun offer(
    frame: TicketVideoDeliveryFrame,
    nowMillis: Long
  ): TicketVideoDeliveryDecision {
    if (closed) {
      return TicketVideoDeliveryDecision(droppedFrames = 1, dropReason = DROP_CLOSED)
    }
    if (!configReady) {
      return TicketVideoDeliveryDecision(droppedFrames = 1, dropReason = DROP_CONFIG_NOT_READY)
    }
    if (inFlightSinceMillis > 0L && nowMillis - inFlightSinceMillis >= slowCloseMillis) {
      return closeSlowWrite(
        blockedMillis = (nowMillis - inFlightSinceMillis).coerceAtLeast(0L),
        additionalDroppedFrames = 2
      )
    }
    if (frame.epoch != expectedEpoch) {
      return TicketVideoDeliveryDecision(droppedFrames = 1, dropReason = DROP_EPOCH_MISMATCH)
    }
    if (!frame.keyFrame) {
      return TicketVideoDeliveryDecision(
        droppedFrames = 1,
        requestImmediateRefresh = true,
        dropReason = DROP_UNEXPECTED_DELTA
      )
    }
    if (latestAcceptedSequence > 0L && frame.sequence <= latestAcceptedSequence) {
      return TicketVideoDeliveryDecision(droppedFrames = 1, dropReason = DROP_STALE_FRAME)
    }
    if (frame.bytes.size > maxFrameBytes) {
      return TicketVideoDeliveryDecision(
        droppedFrames = 1,
        requestImmediateRefresh = true,
        dropReason = DROP_FRAME_TOO_LARGE
      )
    }

    latestAcceptedSequence = frame.sequence
    if (inFlightSinceMillis <= 0L) {
      return startWrite(frame = frame, nowMillis = nowMillis)
    }

    val dropped = if (pendingFrame == null) 0 else 1
    pendingFrame = frame
    return TicketVideoDeliveryDecision(
      droppedFrames = dropped,
      dropReason = DROP_PENDING_REPLACED.takeIf { dropped > 0 }
    )
  }

  @Synchronized
  fun completeWrite(
    writeToken: Long,
    nowMillis: Long,
    succeeded: Boolean
  ): TicketVideoDeliveryDecision {
    if (closed || writeToken <= 0L || inFlightWriteToken != writeToken) {
      return TicketVideoDeliveryDecision()
    }
    val blockedMillis = (nowMillis - inFlightSinceMillis).coerceAtLeast(0L)
    if (blockedMillis >= slowCloseMillis) {
      return closeSlowWrite(
        blockedMillis = blockedMillis,
        additionalDroppedFrames = if (succeeded) 0 else 1
      )
    }
    inFlightSinceMillis = 0L
    inFlightSequence = 0L
    inFlightWriteToken = 0L
    if (!succeeded) {
      val dropped = pendingFrameCount() + 1
      pendingFrame = null
      closed = true
      latestAcceptedSequence = 0L
      return TicketVideoDeliveryDecision(
        droppedFrames = dropped,
        dropReason = DROP_WRITE_FAILED
      )
    }

    val next = pendingFrame ?: return TicketVideoDeliveryDecision()
    pendingFrame = null
    return startWrite(frame = next, nowMillis = nowMillis)
  }

  @Synchronized
  fun timeoutWrite(
    writeToken: Long,
    nowMillis: Long
  ): TicketVideoDeliveryDecision {
    if (
      closed ||
      writeToken <= 0L ||
      inFlightWriteToken != writeToken ||
      inFlightSinceMillis <= 0L
    ) {
      return TicketVideoDeliveryDecision()
    }
    val blockedMillis = (nowMillis - inFlightSinceMillis).coerceAtLeast(0L)
    if (blockedMillis < slowCloseMillis) return TicketVideoDeliveryDecision()
    return closeSlowWrite(blockedMillis = blockedMillis, additionalDroppedFrames = 1)
  }

  @Synchronized
  fun close() {
    closed = true
    inFlightSinceMillis = 0L
    inFlightSequence = 0L
    inFlightWriteToken = 0L
    latestAcceptedSequence = 0L
    pendingFrame = null
  }

  @Synchronized
  fun snapshot(): TicketVideoDeliverySnapshot {
    val pending = pendingFrame
    return TicketVideoDeliverySnapshot(
      expectedEpoch = expectedEpoch,
      configReady = configReady,
      writeInFlight = inFlightSinceMillis > 0L,
      inFlightSequence = inFlightSequence,
      inFlightWriteToken = inFlightWriteToken,
      pendingFrames = pendingFrameCount(),
      pendingBytes = pending?.bytes?.size ?: 0,
      pendingSequence = pending?.sequence ?: 0L,
      latestAcceptedSequence = latestAcceptedSequence,
      closed = closed
    )
  }

  private fun startWrite(
    frame: TicketVideoDeliveryFrame,
    nowMillis: Long
  ): TicketVideoDeliveryDecision {
    nextWriteToken = if (nextWriteToken == Long.MAX_VALUE) 1L else nextWriteToken + 1L
    inFlightSinceMillis = nowMillis.coerceAtLeast(1L)
    inFlightSequence = frame.sequence
    inFlightWriteToken = nextWriteToken
    return TicketVideoDeliveryDecision(
      frameToWrite = frame,
      writeToken = inFlightWriteToken
    )
  }

  private fun closeSlowWrite(
    blockedMillis: Long,
    additionalDroppedFrames: Int
  ): TicketVideoDeliveryDecision {
    val dropped = pendingFrameCount() + additionalDroppedFrames
    pendingFrame = null
    closed = true
    inFlightSinceMillis = 0L
    inFlightSequence = 0L
    inFlightWriteToken = 0L
    latestAcceptedSequence = 0L
    return TicketVideoDeliveryDecision(
      droppedFrames = dropped,
      closeSlowClient = true,
      dropReason = DROP_SLOW_CLIENT,
      blockedMillis = blockedMillis
    )
  }

  private fun pendingFrameCount(): Int = if (pendingFrame == null) 0 else 1

  companion object {
    const val DROP_CLOSED = "closed"
    const val DROP_CONFIG_NOT_READY = "config_not_ready"
    const val DROP_EPOCH_MISMATCH = "epoch_mismatch"
    const val DROP_UNEXPECTED_DELTA = "unexpected_delta"
    const val DROP_STALE_FRAME = "stale_frame"
    const val DROP_FRAME_TOO_LARGE = "frame_too_large"
    const val DROP_PENDING_REPLACED = "pending_replaced"
    const val DROP_SLOW_CLIENT = "slow_client"
    const val DROP_WRITE_FAILED = "write_failed"
  }
}
