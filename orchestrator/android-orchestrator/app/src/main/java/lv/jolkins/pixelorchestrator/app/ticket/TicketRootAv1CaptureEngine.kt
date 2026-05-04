package lv.jolkins.pixelorchestrator.app.ticket

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds

class TicketRootAv1CaptureEngine(
  private val scope: CoroutineScope,
  private val rootExecutor: RootExecutor,
  private val onFrame: (TicketRootCaptureFrame) -> Unit,
  private val onStateChanged: (TicketRootCaptureHealth) -> Unit
) {
  private data class RootRawFrame(
    val width: Int,
    val height: Int,
    val payload: ByteArray,
    val pixelsOffset: Int
  )

  @Volatile private var job: Job? = null
  @Volatile private var process: Process? = null
  @Volatile private var encoder: MediaCodec? = null
  @Volatile private var wanted = AtomicBoolean(false)
  @Volatile private var supported = false
  @Volatile private var state = "idle"
  @Volatile private var message = "Root AV1 capture is idle"
  @Volatile private var encoderName: String? = null
  @Volatile private var colorFormat: Int? = null
  @Volatile private var width: Int? = null
  @Volatile private var height: Int? = null
  @Volatile private var bitrate: Int? = null
  @Volatile private var frames = 0L
  @Volatile private var keyFrames = 0L
  @Volatile private var restarts = 0L
  @Volatile private var suppressedRestartRequests = 0L
  @Volatile private var lastFrameAtMillis = 0L
  @Volatile private var lastKeyFrameAtMillis = 0L
  @Volatile private var lastStartAtMillis = 0L
  @Volatile private var lastRestartAtMillis = 0L
  @Volatile private var lastRestartReason: String? = null
  @Volatile private var lastCaptureDurationMillis: Long? = null
  @Volatile private var lastDecodeDurationMillis: Long? = null
  @Volatile private var lastConvertDurationMillis: Long? = null
  @Volatile private var lastEncodeDrainDurationMillis: Long? = null
  @Volatile private var lastFrameTotalDurationMillis: Long? = null
  @Volatile private var burstUntilMillis = 0L
  @Volatile private var keyFrameRequested = AtomicBoolean(false)
  private val frameRequests = AtomicLong(0L)
  private val restartReasonCounts = Collections.synchronizedMap(mutableMapOf<String, Long>())

  suspend fun probe(): Boolean {
    val root = rootExecutor.isRootAvailable()
    if (!root) {
      supported = false
      state = "unavailable"
      message = "Root shell is unavailable"
      publish()
      return false
    }
    val screencap = rootExecutor.run("command -v screencap >/dev/null 2>&1", timeout = 5.seconds)
    if (!screencap.ok) {
      supported = false
      state = "unavailable"
      message = "screencap is unavailable to root"
      publish()
      return false
    }
    val codecName = TicketAv1Support.hardwareEncoderName()
    if (codecName == null) {
      supported = false
      state = "unavailable"
      message = "Hardware AV1 encoder is unavailable"
      publish()
      return false
    }
    val format = preferredYuvColorFormat(codecName)
    if (format == null) {
      supported = false
      encoderName = codecName
      state = "unavailable"
      message = "Hardware AV1 encoder does not expose a root-feedable YUV input format"
      publish()
      return false
    }
    supported = true
    encoderName = codecName
    colorFormat = format
    state = if (state == "unavailable") "idle" else state
    message = "Root-fed hardware AV1 is available"
    publish()
    return true
  }

  fun start(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int, targetBitrate: Int, fps: Int) {
    width = targetWidth
    height = targetHeight
    bitrate = targetBitrate
    wanted.set(true)
    requestKeyFrame("capture_start")
    if (job?.isActive == true) {
      publish()
      return
    }
    job = scope.launch(Dispatchers.IO) {
      runCaptureLoop(sourceWidth, sourceHeight, targetWidth, targetHeight, targetBitrate, fps)
    }
  }

  fun stop(reason: String = "stopped") {
    wanted.set(false)
    job?.cancel()
    job = null
    stopProcess()
    stopEncoder()
    state = "idle"
    message = "Root AV1 capture stopped: $reason"
    publish()
  }

  fun requestKeyFrame(reason: String) {
    val now = SystemClock.elapsedRealtime()
    burstUntilMillis = max(burstUntilMillis, now + ROOT_AV1_BURST_MILLIS)
    frameRequests.incrementAndGet()
    keyFrameRequested.set(true)
    runCatching {
      encoder?.setParameters(
        Bundle().apply {
          putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        }
      )
    }
    Log.i(TAG, "ticket_root_av1_keyframe_requested reason=$reason")
  }

  fun noteSuppressedRestartRequest(reason: String) {
    suppressedRestartRequests += 1
    Log.i(TAG, "ticket_root_av1_restart_suppressed reason=$reason")
    publish()
  }

  suspend fun cleanupStaleProcesses() {
    stopProcess()
  }

  fun snapshot(nowMillis: Long = SystemClock.elapsedRealtime()): TicketRootCaptureHealth {
    return TicketRootCaptureHealth(
      supported = supported,
      active = job?.isActive == true && encoder != null,
      state = state,
      message = message,
      encoderName = encoderName,
      colorFormat = colorFormatName(colorFormat),
      width = width,
      height = height,
      bitrate = bitrate,
      frames = frames,
      keyFrames = keyFrames,
      lastCaptureDurationMillis = lastCaptureDurationMillis,
      lastDecodeDurationMillis = lastDecodeDurationMillis,
      lastConvertDurationMillis = lastConvertDurationMillis,
      lastEncodeDrainDurationMillis = lastEncodeDrainDurationMillis,
      lastFrameTotalDurationMillis = lastFrameTotalDurationMillis,
      lastFrameAgoMillis = ageMillis(lastFrameAtMillis, nowMillis),
      lastKeyFrameAgoMillis = ageMillis(lastKeyFrameAtMillis, nowMillis),
      lastStartAgoMillis = ageMillis(lastStartAtMillis, nowMillis),
      restarts = restarts,
      restartReasonCounts = synchronized(restartReasonCounts) { restartReasonCounts.toMap() },
      lastRestartReason = lastRestartReason,
      lastRestartAgoMillis = ageMillis(lastRestartAtMillis, nowMillis),
      suppressedRestartRequests = suppressedRestartRequests
    )
  }

  private suspend fun runCaptureLoop(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
    targetBitrate: Int,
    fps: Int
  ) {
    var observedRequests = frameRequests.get()
    var yuv = ByteArray(targetWidth * targetHeight * 3 / 2)
    var sourceXOffsets = scaledXOffsets(sourceWidth, targetWidth)
    var sourceYOffsets = scaledYOffsets(sourceHeight, sourceWidth, targetHeight)
    while (wanted.get()) {
      var localEncoder: MediaCodec? = null
      try {
        localEncoder = startEncoder(targetWidth, targetHeight, targetBitrate, fps)
        encoder = localEncoder
        state = "active"
        message = "Root AV1 capture is active"
        publish()
        val info = MediaCodec.BufferInfo()
        while (wanted.get()) {
          val frameStart = SystemClock.elapsedRealtime()
          val captureStart = frameStart
          val raw = captureRootFrame() ?: continue
          val captureEnd = SystemClock.elapsedRealtime()
          lastCaptureDurationMillis = captureEnd - captureStart
          lastDecodeDurationMillis = 0L
          val convertStart = captureEnd
          if (raw.width != sourceWidth || raw.height != sourceHeight) {
            sourceXOffsets = scaledXOffsets(raw.width, targetWidth)
            sourceYOffsets = scaledYOffsets(raw.height, raw.width, targetHeight)
          }
          fillYuv420FromRaw(raw, yuv, targetWidth, targetHeight, colorFormat, sourceXOffsets, sourceYOffsets)
          val convertEnd = SystemClock.elapsedRealtime()
          lastConvertDurationMillis = convertEnd - convertStart
          val lastKeyFrameAge = ageMillis(lastKeyFrameAtMillis, convertEnd)
          if (lastKeyFrameAge == null || lastKeyFrameAge > TicketScreenConfig.AV1_KEYFRAME_INTERVAL_MILLIS) {
            keyFrameRequested.set(true)
          }
          queueFrame(localEncoder, yuv, SystemClock.elapsedRealtimeNanos() / 1_000L)
          val drainStart = convertEnd
          drainEncoder(localEncoder, info)
          val drainEnd = SystemClock.elapsedRealtime()
          lastEncodeDrainDurationMillis = drainEnd - drainStart
          lastFrameTotalDurationMillis = drainEnd - frameStart
          publish()
          val interval = if (SystemClock.elapsedRealtime() < burstUntilMillis) {
            ROOT_AV1_BURST_INTERVAL_MILLIS
          } else {
            ROOT_AV1_STEADY_INTERVAL_MILLIS
          }
          val until = SystemClock.elapsedRealtime() + interval
          while (wanted.get() && SystemClock.elapsedRealtime() < until) {
            val currentRequests = frameRequests.get()
            if (currentRequests != observedRequests) {
              observedRequests = currentRequests
              break
            }
            delay(ROOT_AV1_REQUEST_POLL_MILLIS)
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        if (wanted.get()) {
          recordRestart(error.message ?: error::class.java.simpleName)
          state = "restarting"
          message = "Root AV1 capture failed: ${error.message ?: error::class.java.simpleName}"
          Log.w(TAG, "ticket_root_av1_capture_failed", error)
          publish()
          delay(ROOT_AV1_RESTART_DELAY_MILLIS)
        }
      } finally {
        runCatching { localEncoder?.stop() }
        runCatching { localEncoder?.release() }
        if (encoder == localEncoder) {
          encoder = null
        }
      }
      if (yuv.size != targetWidth * targetHeight * 3 / 2) {
        yuv = ByteArray(targetWidth * targetHeight * 3 / 2)
      }
    }
    state = "idle"
    message = "Root AV1 capture is idle"
    publish()
  }

  private fun startEncoder(width: Int, height: Int, bitrate: Int, fps: Int): MediaCodec {
    val codecName = encoderName ?: TicketAv1Support.hardwareEncoderName()
      ?: error("Hardware AV1 encoder is unavailable")
    val format = colorFormat ?: preferredYuvColorFormat(codecName)
      ?: error("Hardware AV1 encoder does not expose a root-feedable YUV input format")
    encoderName = codecName
    colorFormat = format
    val mediaFormat = MediaFormat.createVideoFormat(TicketScreenConfig.AV1_MIME_TYPE, width, height).apply {
      setInteger(MediaFormat.KEY_COLOR_FORMAT, format)
      setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
      setInteger(MediaFormat.KEY_FRAME_RATE, fps)
      setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, TicketScreenConfig.AV1_KEYFRAME_INTERVAL_SECONDS)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
      }
      setInteger("priority", 0)
      setInteger("low-latency", 1)
    }
    val localEncoder = MediaCodec.createByCodecName(codecName)
    localEncoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    localEncoder.start()
    lastStartAtMillis = SystemClock.elapsedRealtime()
    Log.i(TAG, "ticket_root_av1_encoder_started name=$codecName color=${colorFormatName(format)} width=$width height=$height bitrate=$bitrate")
    return localEncoder
  }

  private suspend fun captureRootFrame(): RootRawFrame? = withContext(Dispatchers.IO) {
    state = "capturing"
    message = "Root AV1 capture is reading a fresh root frame"
    publish()
    val localProcess = ProcessBuilder("su", "-c", "screencap")
      .redirectErrorStream(false)
      .start()
    process = localProcess
    val payload = localProcess.inputStream.use { it.readBytes() }
    val stderr = localProcess.errorStream.bufferedReader().use { it.readText() }.trim()
    val exitCode = withTimeoutOrNull(ROOT_AV1_CAPTURE_TIMEOUT_MILLIS) {
      localProcess.waitFor()
    }
    process = null
    if (exitCode == null) {
      runCatching { localProcess.destroyForcibly() }
      recordRestart("capture_timeout")
      return@withContext null
    }
    if (exitCode != 0) {
      if (stderr.isNotBlank()) {
        Log.w(TAG, "ticket_root_av1_capture_stderr $stderr")
      }
      recordRestart("capture_exit_$exitCode")
      return@withContext null
    }
    val raw = parseRawFrame(payload)
    if (raw == null) {
      recordRestart("capture_raw_invalid")
      return@withContext null
    }
    raw
  }

  private fun queueFrame(localEncoder: MediaCodec, yuv: ByteArray, presentationTimeUs: Long) {
    val index = localEncoder.dequeueInputBuffer(ROOT_AV1_ENCODER_TIMEOUT_US)
    if (index < 0) {
      recordRestart("input_buffer_timeout")
      return
    }
    val input = localEncoder.getInputBuffer(index) ?: return
    input.clear()
    input.put(yuv, 0, yuv.size)
    if (keyFrameRequested.getAndSet(false)) {
      runCatching {
        localEncoder.setParameters(
          Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
          }
        )
      }
    }
    localEncoder.queueInputBuffer(index, 0, yuv.size, presentationTimeUs, 0)
  }

  private fun drainEncoder(localEncoder: MediaCodec, info: MediaCodec.BufferInfo) {
    while (true) {
      when (val index = localEncoder.dequeueOutputBuffer(info, ROOT_AV1_ENCODER_TIMEOUT_US)) {
        MediaCodec.INFO_TRY_AGAIN_LATER -> return
        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> return
        else -> if (index >= 0) {
          val output = localEncoder.getOutputBuffer(index)
          if (output != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
            output.position(info.offset)
            output.limit(info.offset + info.size)
            val payload = ByteArray(info.size)
            output.get(payload)
            val keyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            val now = SystemClock.elapsedRealtime()
            frames += 1
            lastFrameAtMillis = now
            if (keyFrame) {
              keyFrames += 1
              lastKeyFrameAtMillis = now
            }
            onFrame(
              TicketRootCaptureFrame(
                keyFrame = keyFrame,
                timestampUs = info.presentationTimeUs,
                payload = payload,
                width = width ?: 0,
                height = height ?: 0
              )
            )
          }
          localEncoder.releaseOutputBuffer(index, false)
        }
      }
    }
  }

  private fun stopProcess() {
    val local = process
    process = null
    if (local != null) {
      runCatching { local.destroy() }
      runCatching { local.destroyForcibly() }
    }
  }

  private fun stopEncoder() {
    val local = encoder
    encoder = null
    if (local != null) {
      runCatching { local.stop() }
      runCatching { local.release() }
    }
  }

  private fun recordRestart(reason: String) {
    restarts += 1
    lastRestartAtMillis = SystemClock.elapsedRealtime()
    lastRestartReason = reason
    synchronized(restartReasonCounts) {
      restartReasonCounts[reason] = (restartReasonCounts[reason] ?: 0L) + 1L
    }
    Log.i(TAG, "ticket_root_av1_capture_restart reason=$reason restarts=$restarts")
  }

  private fun publish() {
    onStateChanged(snapshot())
  }

  private fun ageMillis(timestampMillis: Long, nowMillis: Long): Long? {
    return timestampMillis.takeIf { it > 0L }?.let { (nowMillis - it).coerceAtLeast(0L) }
  }

  private fun preferredYuvColorFormat(codecName: String): Int? {
    val info = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos
      .firstOrNull { it.name == codecName }
      ?: return null
    val formats = info.getCapabilitiesForType(TicketScreenConfig.AV1_MIME_TYPE).colorFormats.toSet()
    return when {
      MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar in formats ->
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
      MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar in formats ->
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
      MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in formats ->
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
      else -> null
    }
  }

  private fun parseRawFrame(payload: ByteArray): RootRawFrame? {
    if (payload.size < RAW_SCREENCAP_HEADER_BYTES) {
      return null
    }
    val sourceWidth = payload.readLittleEndianInt(0)
    val sourceHeight = payload.readLittleEndianInt(4)
    val format = payload.readLittleEndianInt(8)
    val pixelsOffset = if (payload.size >= 16 + sourceWidth * sourceHeight * 4) 16 else 12
    if (
      sourceWidth <= 0 ||
      sourceHeight <= 0 ||
      format != RAW_FORMAT_RGBA_8888 ||
      payload.size < pixelsOffset + sourceWidth * sourceHeight * 4
    ) {
      return null
    }
    return RootRawFrame(
      width = sourceWidth,
      height = sourceHeight,
      payload = payload,
      pixelsOffset = pixelsOffset
    )
  }

  private fun fillYuv420FromRaw(
    raw: RootRawFrame,
    yuv: ByteArray,
    width: Int,
    height: Int,
    format: Int?,
    sourceXOffsets: IntArray,
    sourceYOffsets: IntArray
  ) {
    val frameSize = width * height
    var yIndex = 0
    var uvIndex = frameSize
    val planar = format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
    val uPlaneStart = frameSize
    val vPlaneStart = frameSize + frameSize / 4
    val payload = raw.payload
    val offset = raw.pixelsOffset
    for (j in 0 until height) {
      val sourceRowOffset = offset + sourceYOffsets[j]
      for (i in 0 until width) {
        val pixelOffset = sourceRowOffset + sourceXOffsets[i]
        val r = payload[pixelOffset].toInt() and 0xff
        val g = payload[pixelOffset + 1].toInt() and 0xff
        val b = payload[pixelOffset + 2].toInt() and 0xff
        val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
        val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
        val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
        yuv[yIndex++] = y.coerceIn(0, 255).toByte()
        if (j % 2 == 0 && i % 2 == 0) {
          if (planar) {
            val chroma = (j / 2) * (width / 2) + (i / 2)
            yuv[uPlaneStart + chroma] = u.coerceIn(0, 255).toByte()
            yuv[vPlaneStart + chroma] = v.coerceIn(0, 255).toByte()
          } else {
            yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
            yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
          }
        }
      }
    }
  }

  private fun scaledXOffsets(sourceWidth: Int, targetWidth: Int): IntArray {
    return IntArray(targetWidth) { x ->
      (((x.toLong() * sourceWidth) / targetWidth).toInt().coerceIn(0, sourceWidth - 1)) * 4
    }
  }

  private fun scaledYOffsets(sourceHeight: Int, sourceWidth: Int, targetHeight: Int): IntArray {
    return IntArray(targetHeight) { y ->
      ((y.toLong() * sourceHeight) / targetHeight).toInt().coerceIn(0, sourceHeight - 1) * sourceWidth * 4
    }
  }

  private fun ByteArray.readLittleEndianInt(offset: Int): Int {
    return (this[offset].toInt() and 0xff) or
      ((this[offset + 1].toInt() and 0xff) shl 8) or
      ((this[offset + 2].toInt() and 0xff) shl 16) or
      ((this[offset + 3].toInt() and 0xff) shl 24)
  }

  private fun colorFormatName(format: Int?): String? {
    return when (format) {
      MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> "YUV420SemiPlanar"
      MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> "YUV420Planar"
      MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> "YUV420Flexible"
      null -> null
      else -> "0x${format.toString(16)}"
    }
  }

  private companion object {
    private const val TAG = "TicketRootAv1Capture"
    private const val ROOT_AV1_BURST_MILLIS = 2_000L
    private const val ROOT_AV1_BURST_INTERVAL_MILLIS = 100L
    private const val ROOT_AV1_STEADY_INTERVAL_MILLIS = 125L
    private const val ROOT_AV1_REQUEST_POLL_MILLIS = 25L
    private const val ROOT_AV1_CAPTURE_TIMEOUT_MILLIS = 2_000L
    private const val ROOT_AV1_ENCODER_TIMEOUT_US = 50_000L
    private const val ROOT_AV1_RESTART_DELAY_MILLIS = 200L
    private const val RAW_SCREENCAP_HEADER_BYTES = 12
    private const val RAW_FORMAT_RGBA_8888 = 1
  }
}
