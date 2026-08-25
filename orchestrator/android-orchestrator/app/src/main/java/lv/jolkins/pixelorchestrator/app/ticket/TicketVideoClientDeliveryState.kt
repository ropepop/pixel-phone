package lv.jolkins.pixelorchestrator.app.ticket

import java.util.ArrayDeque

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
  val requestKeyFrame: Boolean = false,
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
  val queuedFrames: Int,
  val queuedBytes: Int,
  val waitingForKeyFrame: Boolean,
  val lastAdmittedEpoch: Long,
  val lastAdmittedSequence: Long,
  val keyFrameRequestPending: Boolean,
  val closed: Boolean
)

/**
 * Per-viewer, bounded delivery state for already-numbered Ticket video frames.
 *
 * A single writer drains the queue in order. Normal encoder bursts therefore retain every
 * admitted sequence instead of replacing a one-slot "latest" frame. If the bounded queue really
 * overflows, dependent deltas are discarded together and delivery resumes only from a keyframe.
 */
internal class TicketVideoClientDeliveryState(
  internal val expectedEpoch: Long,
  private val maxQueuedFrames: Int,
  private val maxQueuedBytes: Int,
  private val pendingMaxAgeMillis: Long,
  private val slowCloseMillis: Long
) {
  private data class QueuedFrame(
    val frame: TicketVideoDeliveryFrame,
    val queuedAtMillis: Long
  )

  private val queued = ArrayDeque<QueuedFrame>()
  private var queuedBytes = 0
  private var configReady = false
  private var inFlightSinceMillis = 0L
  private var inFlightSequence = 0L
  private var inFlightWriteToken = 0L
  private var nextWriteToken = 0L
  private var waitingForKeyFrame = true
  private var lastAdmittedEpoch = 0L
  private var lastAdmittedSequence = 0L
  private var keyFrameRequestPending = false
  private var closed = false

  init {
    require(expectedEpoch >= 0L)
    require(maxQueuedFrames > 0)
    require(maxQueuedBytes > 0)
    require(pendingMaxAgeMillis >= 0L)
    require(slowCloseMillis >= pendingMaxAgeMillis)
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
    if (frame.epoch != expectedEpoch) {
      return TicketVideoDeliveryDecision(
        droppedFrames = 1,
        requestKeyFrame = armKeyFrameRequest(),
        dropReason = DROP_EPOCH_MISMATCH
      )
    }
    if (inFlightSinceMillis > 0L && nowMillis - inFlightSinceMillis >= slowCloseMillis) {
      val blockedMillis = (nowMillis - inFlightSinceMillis).coerceAtLeast(0L)
      return closeSlowWrite(
        blockedMillis = blockedMillis,
        additionalDroppedFrames = 2
      )
    }
    if (waitingForKeyFrame && !frame.keyFrame) {
      return TicketVideoDeliveryDecision(
        droppedFrames = 1,
        requestKeyFrame = armKeyFrameRequest(),
        dropReason = DROP_WAITING_FOR_KEYFRAME
      )
    }
    if (
      frame.keyFrame &&
      lastAdmittedEpoch == frame.epoch &&
      lastAdmittedSequence > 0L &&
      frame.sequence <= lastAdmittedSequence
    ) {
      return TicketVideoDeliveryDecision(
        droppedFrames = 1,
        requestKeyFrame = armKeyFrameRequest(),
        dropReason = DROP_STALE_KEYFRAME
      )
    }
    if (
      !frame.keyFrame &&
      (
        lastAdmittedEpoch != frame.epoch ||
        lastAdmittedSequence <= 0L ||
        frame.sequence != lastAdmittedSequence + 1L
      )
    ) {
      val dropped = queued.size + 1
      clearQueued()
      waitingForKeyFrame = true
      lastAdmittedEpoch = 0L
      lastAdmittedSequence = 0L
      return TicketVideoDeliveryDecision(
        droppedFrames = dropped,
        requestKeyFrame = armKeyFrameRequest(),
        dropReason = DROP_SEQUENCE_GAP
      )
    }
    if (frame.bytes.size > maxQueuedBytes) {
      val dropped = queued.size + 1
      clearQueued()
      waitingForKeyFrame = true
      lastAdmittedEpoch = 0L
      lastAdmittedSequence = 0L
      return TicketVideoDeliveryDecision(
        droppedFrames = dropped,
        requestKeyFrame = armKeyFrameRequest(),
        dropReason = DROP_QUEUE_OVERFLOW
      )
    }
    if (inFlightSinceMillis <= 0L) {
      if (frame.keyFrame) acceptKeyFrame()
      lastAdmittedEpoch = frame.epoch
      lastAdmittedSequence = frame.sequence
      return startWrite(frame = frame, nowMillis = nowMillis)
    }

    val fits = queued.size < maxQueuedFrames && queuedBytes + frame.bytes.size <= maxQueuedBytes
    if (fits) {
      if (frame.keyFrame) acceptKeyFrame()
      queued.addLast(QueuedFrame(frame = frame, queuedAtMillis = nowMillis))
      queuedBytes += frame.bytes.size
      lastAdmittedEpoch = frame.epoch
      lastAdmittedSequence = frame.sequence
      return TicketVideoDeliveryDecision()
    }

    val dropped = queued.size
    clearQueued()
    if (frame.keyFrame) {
      acceptKeyFrame()
      queued.addLast(QueuedFrame(frame = frame, queuedAtMillis = nowMillis))
      queuedBytes = frame.bytes.size
      lastAdmittedEpoch = frame.epoch
      lastAdmittedSequence = frame.sequence
      return TicketVideoDeliveryDecision(
        droppedFrames = dropped,
        dropReason = DROP_QUEUE_OVERFLOW
      )
    }
    waitingForKeyFrame = true
    lastAdmittedEpoch = 0L
    lastAdmittedSequence = 0L
    return TicketVideoDeliveryDecision(
      droppedFrames = dropped + 1,
      requestKeyFrame = armKeyFrameRequest(),
      dropReason = DROP_QUEUE_OVERFLOW
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
      val dropped = queued.size + 1
      clearQueued()
      closed = true
      lastAdmittedEpoch = 0L
      lastAdmittedSequence = 0L
      keyFrameRequestPending = false
      return TicketVideoDeliveryDecision(
        droppedFrames = dropped,
        dropReason = DROP_WRITE_FAILED
      )
    }
    if (queued.isEmpty()) return TicketVideoDeliveryDecision()

    val oldest = requireNotNull(queued.peekFirst())
    if (nowMillis - oldest.queuedAtMillis > pendingMaxAgeMillis) {
      val freshKeyFrameIndex = queued.indexOfLast { queuedFrame ->
        queuedFrame.frame.keyFrame && nowMillis - queuedFrame.queuedAtMillis <= pendingMaxAgeMillis
      }
      if (freshKeyFrameIndex < 0) {
        val dropped = queued.size
        clearQueued()
        waitingForKeyFrame = true
        lastAdmittedEpoch = 0L
        lastAdmittedSequence = 0L
        return TicketVideoDeliveryDecision(
          droppedFrames = dropped,
          requestKeyFrame = armKeyFrameRequest(),
          dropReason = DROP_QUEUE_STALE
        )
      }
      var dropped = 0
      repeat(freshKeyFrameIndex) {
        removeFirstQueued()
        dropped += 1
      }
      val next = removeFirstQueued().frame
      waitingForKeyFrame = false
      keyFrameRequestPending = false
      return startWrite(
        frame = next,
        nowMillis = nowMillis,
        droppedFrames = dropped,
        dropReason = DROP_QUEUE_STALE
      )
    }

    val next = removeFirstQueued().frame
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
    lastAdmittedSequence = 0L
    lastAdmittedEpoch = 0L
    keyFrameRequestPending = false
    clearQueued()
  }

  @Synchronized
  fun snapshot(): TicketVideoDeliverySnapshot {
    return TicketVideoDeliverySnapshot(
      expectedEpoch = expectedEpoch,
      configReady = configReady,
      writeInFlight = inFlightSinceMillis > 0L,
      inFlightSequence = inFlightSequence,
      inFlightWriteToken = inFlightWriteToken,
      queuedFrames = queued.size,
      queuedBytes = queuedBytes,
      waitingForKeyFrame = waitingForKeyFrame,
      lastAdmittedEpoch = lastAdmittedEpoch,
      lastAdmittedSequence = lastAdmittedSequence,
      keyFrameRequestPending = keyFrameRequestPending,
      closed = closed
    )
  }

  private fun removeFirstQueued(): QueuedFrame {
    val removed = queued.removeFirst()
    queuedBytes = (queuedBytes - removed.frame.bytes.size).coerceAtLeast(0)
    return removed
  }

  private fun clearQueued() {
    queued.clear()
    queuedBytes = 0
  }

  private fun startWrite(
    frame: TicketVideoDeliveryFrame,
    nowMillis: Long,
    droppedFrames: Int = 0,
    dropReason: String? = null
  ): TicketVideoDeliveryDecision {
    nextWriteToken = if (nextWriteToken == Long.MAX_VALUE) 1L else nextWriteToken + 1L
    inFlightSinceMillis = nowMillis.coerceAtLeast(1L)
    inFlightSequence = frame.sequence
    inFlightWriteToken = nextWriteToken
    return TicketVideoDeliveryDecision(
      frameToWrite = frame,
      writeToken = inFlightWriteToken,
      droppedFrames = droppedFrames,
      dropReason = dropReason
    )
  }

  private fun acceptKeyFrame() {
    waitingForKeyFrame = false
    keyFrameRequestPending = false
  }

  private fun closeSlowWrite(
    blockedMillis: Long,
    additionalDroppedFrames: Int
  ): TicketVideoDeliveryDecision {
    val dropped = queued.size + additionalDroppedFrames
    clearQueued()
    closed = true
    inFlightSinceMillis = 0L
    inFlightSequence = 0L
    inFlightWriteToken = 0L
    lastAdmittedSequence = 0L
    lastAdmittedEpoch = 0L
    keyFrameRequestPending = false
    return TicketVideoDeliveryDecision(
      droppedFrames = dropped,
      closeSlowClient = true,
      dropReason = DROP_SLOW_CLIENT,
      blockedMillis = blockedMillis
    )
  }

  private fun armKeyFrameRequest(): Boolean {
    if (keyFrameRequestPending) return false
    keyFrameRequestPending = true
    return true
  }

  companion object {
    const val DROP_CLOSED = "closed"
    const val DROP_CONFIG_NOT_READY = "config_not_ready"
    const val DROP_WAITING_FOR_KEYFRAME = "waiting_for_keyframe"
    const val DROP_STALE_KEYFRAME = "stale_keyframe"
    const val DROP_EPOCH_MISMATCH = "epoch_mismatch"
    const val DROP_SEQUENCE_GAP = "sequence_gap"
    const val DROP_SLOW_CLIENT = "slow_client"
    const val DROP_QUEUE_OVERFLOW = "queue_overflow"
    const val DROP_QUEUE_STALE = "queue_stale"
    const val DROP_WRITE_FAILED = "write_failed"
  }
}
