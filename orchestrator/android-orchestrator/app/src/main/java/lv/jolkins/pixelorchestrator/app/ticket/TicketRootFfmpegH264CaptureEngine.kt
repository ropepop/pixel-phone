package lv.jolkins.pixelorchestrator.app.ticket

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Duration.Companion.seconds

class TicketRootFfmpegH264CaptureEngine(
  private val scope: CoroutineScope,
  private val rootExecutor: RootExecutor,
  private val onFrame: (TicketRootCaptureFrame) -> Unit,
  private val onStateChanged: (TicketFfmpegHealth) -> Unit
) {
  @Volatile private var job: Job? = null
  @Volatile private var ffmpegProcess: Process? = null
  @Volatile private var feederProcess: Process? = null
  @Volatile private var wanted = false
  @Volatile private var available = false
  @Volatile private var state = "idle"
  @Volatile private var message = "FFmpeg capture is idle"
  @Volatile private var version: String? = null
  @Volatile private var binarySha: String? = null
  @Volatile private var encoderName: String? = null
  @Volatile private var width: Int? = null
  @Volatile private var height: Int? = null
  @Volatile private var bitrate: Int? = null
  @Volatile private var fps: Int? = null
  @Volatile private var frames = 0L
  @Volatile private var keyFrames = 0L
  @Volatile private var lastFrameBytes = 0
  @Volatile private var lastFrameAtMillis = 0L
  @Volatile private var lastStartAtMillis = 0L
  @Volatile private var lastRootFrameReadDurationMillis: Long? = null
  @Volatile private var lastFfmpegWriteDurationMillis: Long? = null
  @Volatile private var lastFrameTotalDurationMillis: Long? = null
  @Volatile private var suppressedRawFrames = 0L
  @Volatile private var lastSuppressedRawFrameAtMillis = 0L
  @Volatile private var firstVisibleRawFrameAtMillis = 0L
  @Volatile private var lastRawFrameVisible = false
  @Volatile private var droppedFrames = 0L
  @Volatile private var restartCount = 0L
  @Volatile private var lastExitReason: String? = null
  @Volatile private var lastExitAtMillis = 0L
  @Volatile private var stderrTail = ""
  @Volatile private var estimatedBitrate = 0L
  @Volatile private var bitrateWindowStartedAtMillis = 0L
  @Volatile private var bitrateWindowBytes = 0L
  @Volatile private var captureHelperAvailable = false
  @Volatile private var captureHelperState = "unavailable"
  @Volatile private var captureHelperMessage = "Root SurfaceControl capture helper has not been probed"

  suspend fun probe(sourceWidth: Int? = null, sourceHeight: Int? = null): Boolean {
    if (!rootExecutor.isRootAvailable()) {
      available = false
      state = "unavailable"
      message = "Root shell is unavailable"
      captureHelperAvailable = false
      captureHelperState = "unavailable"
      captureHelperMessage = message
      publish()
      return false
    }
    val ffmpeg = rootExecutor.run(
      """
        test -x '${TicketScreenConfig.ROOT_FFMPEG_H264_CHROOT}${TicketScreenConfig.ROOT_FFMPEG_H264_BINARY}' &&
        chroot '${TicketScreenConfig.ROOT_FFMPEG_H264_CHROOT}' /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
          '${TicketScreenConfig.ROOT_FFMPEG_H264_BINARY}' -hide_banner -version | head -n 1
      """.trimIndent(),
      timeout = 8.seconds
    )
    if (!ffmpeg.ok) {
      available = false
      state = "unavailable"
      message = "FFmpeg is not installed in the ticket chroot"
      captureHelperAvailable = false
      captureHelperState = "unavailable"
      captureHelperMessage = message
      publish()
      return false
    }
    val encoder = rootExecutor.run(
      """
        chroot '${TicketScreenConfig.ROOT_FFMPEG_H264_CHROOT}' /usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
          '${TicketScreenConfig.ROOT_FFMPEG_H264_BINARY}' -hide_banner -encoders 2>/dev/null | grep -q 'libx264'
      """.trimIndent(),
      timeout = 8.seconds
    )
    if (!encoder.ok) {
      available = false
      state = "unavailable"
      message = "FFmpeg is installed but libx264 is unavailable"
      captureHelperAvailable = false
      captureHelperState = "unavailable"
      captureHelperMessage = message
      publish()
      return false
    }
    val helper = if (sourceWidth != null && sourceHeight != null) {
      probeCaptureHelper(sourceWidth, sourceHeight)
    } else {
      rootExecutor.run("command -v app_process >/dev/null 2>&1", timeout = 5.seconds)
    }
    if (!helper.ok) {
      available = false
      captureHelperAvailable = false
      captureHelperState = "unavailable"
      captureHelperMessage = helper.stderr.trim().ifBlank {
        helper.stdout.trim().ifBlank { "Root SurfaceControl capture helper is unavailable" }
      }.takeLast(240)
      state = "unavailable"
      message = captureHelperMessage
      publish()
      return false
    }
    val sha = rootExecutor.run(
      "sha256sum '${TicketScreenConfig.ROOT_FFMPEG_H264_CHROOT}${TicketScreenConfig.ROOT_FFMPEG_H264_BINARY}' 2>/dev/null | awk '{print $1}'",
      timeout = 5.seconds
    )
    available = true
    version = ffmpeg.stdout.lineSequence().firstOrNull()?.trim()?.ifBlank { null }
    binarySha = sha.stdout.trim().ifBlank { null }
    encoderName = "libx264"
    captureHelperAvailable = true
    captureHelperState = if (sourceWidth != null && sourceHeight != null) "ready" else "installed"
    captureHelperMessage = if (sourceWidth != null && sourceHeight != null) {
      "Root SurfaceControl capture helper produced a test frame"
    } else {
      "app_process is available; SurfaceControl helper will be verified at stream start"
    }
    state = if (state == "unavailable") "idle" else state
    message = "FFmpeg H.264 capture is available"
    publish()
    return true
  }

  fun start(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int, targetBitrate: Int, targetFps: Int) {
    width = targetWidth
    height = targetHeight
    bitrate = targetBitrate
    fps = targetFps
    wanted = true
    if (job?.isActive == true) {
      publish()
      return
    }
    firstVisibleRawFrameAtMillis = 0L
    lastSuppressedRawFrameAtMillis = 0L
    lastRawFrameVisible = false
    job = scope.launch(Dispatchers.IO) {
      runCaptureLoop(targetWidth, targetHeight, targetBitrate, targetFps)
    }
  }

  fun stop(reason: String = "stopped") {
    wanted = false
    job?.cancel()
    job = null
    stopProcesses()
    scope.launch(Dispatchers.IO + NonCancellable) {
      cleanupMatchingProcesses()
    }
    state = "idle"
    message = if (available) "FFmpeg H.264 capture is available" else "FFmpeg capture stopped: $reason"
    clearFrameState()
    publish()
  }

  fun requestKeyFrame(reason: String) {
    Log.i(TAG, "ticket_ffmpeg_h264_keyframe_requested reason=$reason")
  }

  suspend fun cleanupStaleProcesses() {
    stopProcesses()
    cleanupMatchingProcesses()
    clearFrameState()
    state = if (available) "idle" else state
    message = if (available) "FFmpeg H.264 capture is available" else message
    publish()
  }

  fun snapshot(nowMillis: Long = SystemClock.elapsedRealtime()): TicketFfmpegHealth {
    return TicketFfmpegHealth(
      available = available,
      active = job?.isActive == true && ffmpegProcess?.isAlive == true,
      version = version,
      binarySha = binarySha,
      encoderName = encoderName,
      frameFeederActive = feederProcess?.isAlive == true,
      captureSource = TicketScreenConfig.ROOT_FFMPEG_H264_CAPTURE_SOURCE,
      captureMethod = TicketScreenConfig.ROOT_FFMPEG_H264_CAPTURE_METHOD,
      captureHelperAvailable = captureHelperAvailable,
      captureHelperState = captureHelperState,
      captureHelperMessage = captureHelperMessage,
      state = state,
      message = message,
      width = width,
      height = height,
      bitrate = bitrate,
      fps = fps,
      frames = frames,
      keyFrames = keyFrames,
      lastFrameBytes = lastFrameBytes,
      estimatedBitrate = estimatedBitrate,
      lastFrameAgoMillis = ageMillis(lastFrameAtMillis, nowMillis),
      lastStartAgoMillis = ageMillis(lastStartAtMillis, nowMillis),
      lastRootFrameReadDurationMillis = lastRootFrameReadDurationMillis,
      lastFfmpegWriteDurationMillis = lastFfmpegWriteDurationMillis,
      lastFrameTotalDurationMillis = lastFrameTotalDurationMillis,
      suppressedRawFrames = suppressedRawFrames,
      lastSuppressedRawFrameAgoMillis = ageMillis(lastSuppressedRawFrameAtMillis, nowMillis),
      firstVisibleRawFrameAgoMillis = ageMillis(firstVisibleRawFrameAtMillis, nowMillis),
      lastRawFrameVisible = lastRawFrameVisible,
      droppedFrames = droppedFrames,
      restartCount = restartCount,
      lastExitReason = lastExitReason,
      lastExitAgoMillis = ageMillis(lastExitAtMillis, nowMillis),
      stderrTail = stderrTail
    )
  }

  private suspend fun runCaptureLoop(width: Int, height: Int, targetBitrate: Int, targetFps: Int) {
    while (wanted) {
      val parser = TicketH264AnnexBParser { payload, keyFrame ->
        val now = SystemClock.elapsedRealtime()
        frames += 1
        lastFrameAtMillis = now
        lastFrameBytes = payload.size
        updateEstimatedBitrate(payload.size, now)
        if (keyFrame) {
          keyFrames += 1
        }
        onFrame(
          TicketRootCaptureFrame(
            keyFrame = keyFrame,
            timestampUs = SystemClock.elapsedRealtimeNanos() / 1_000L,
            payload = payload,
            width = width,
            height = height
          )
        )
      }
      var outputJob: Job? = null
      try {
        state = "starting"
        message = "FFmpeg H.264 capture is starting"
        lastStartAtMillis = SystemClock.elapsedRealtime()
        publish()
        val localFfmpeg = ProcessBuilder("su", "-c", ffmpegCommand(width, height, targetBitrate, targetFps))
          .redirectErrorStream(false)
          .start()
        ffmpegProcess = localFfmpeg
        val localFeeder = ProcessBuilder("su", "-c", rootFeederCommand(width, height, targetFps))
          .redirectErrorStream(false)
          .start()
        feederProcess = localFeeder
        outputJob = scope.launch(Dispatchers.IO) {
          readFfmpegOutput(localFfmpeg.inputStream, parser)
        }
        readStderrTail(localFfmpeg)
        readStderrTail(localFeeder)
        state = "active"
        message = "FFmpeg H.264 capture is active"
        publish()
        feedRawFrames(localFeeder.inputStream, localFfmpeg.outputStream, width, height)
        parser.finish()
        val ffmpegExit = withContext(Dispatchers.IO) { runCatching { localFfmpeg.waitFor() }.getOrDefault(-1) }
        if (wanted) {
          recordRestart("ffmpeg_exit_$ffmpegExit")
          state = "restarting"
          message = "FFmpeg exited with code $ffmpegExit; restarting"
          publish()
          delay(RESTART_DELAY_MILLIS)
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        if (wanted) {
          recordRestart("failure")
          state = "restarting"
          message = "FFmpeg capture failed: ${error.message ?: error::class.java.simpleName}"
          Log.w(TAG, "ticket_ffmpeg_h264_capture_failed", error)
          publish()
          delay(RESTART_DELAY_MILLIS)
        }
      } finally {
        outputJob?.cancel()
        withContext(NonCancellable) {
          stopProcesses()
          cleanupMatchingProcesses()
        }
      }
    }
    state = "idle"
    message = "FFmpeg H.264 capture is idle"
    publish()
  }

  private suspend fun feedRawFrames(
    inputStream: InputStream,
    ffmpegInput: OutputStream,
    sourceWidth: Int,
    sourceHeight: Int
  ) = withContext(Dispatchers.IO) {
    val input = BufferedInputStream(inputStream, RAW_FRAME_BYTES_BUFFER)
    val header = ByteArray(RAW_FRAME_HEADER_BYTES)
    val pixels = ByteArray(sourceWidth * sourceHeight * 4)
    while (wanted) {
      val start = SystemClock.elapsedRealtime()
      if (!input.readFully(header, RAW_FRAME_HEADER_BYTES)) {
        break
      }
      val frameWidth = header.readLittleEndianInt(0)
      val frameHeight = header.readLittleEndianInt(4)
      val format = header.readLittleEndianInt(8)
      if (frameWidth != sourceWidth || frameHeight != sourceHeight || format != RAW_FORMAT_RGBA_8888) {
        droppedFrames += 1
        throw IOException("Unexpected native capture frame ${frameWidth}x$frameHeight format=$format")
      }
      if (!input.readFully(pixels, pixels.size)) {
        break
      }
      val readEnd = SystemClock.elapsedRealtime()
      lastRootFrameReadDurationMillis = readEnd - start
      if (!rawRgbaFrameLooksVisible(pixels, sourceWidth, sourceHeight)) {
        droppedFrames += 1
        suppressedRawFrames += 1
        lastSuppressedRawFrameAtMillis = readEnd
        lastRawFrameVisible = false
        lastFrameTotalDurationMillis = readEnd - start
        if (suppressedRawFrames <= 3L || suppressedRawFrames % 10L == 0L) {
          publish()
        }
        continue
      }
      if (firstVisibleRawFrameAtMillis == 0L) {
        firstVisibleRawFrameAtMillis = readEnd
        publish()
      }
      lastRawFrameVisible = true
      ffmpegInput.write(pixels)
      ffmpegInput.flush()
      val writeEnd = SystemClock.elapsedRealtime()
      lastFfmpegWriteDurationMillis = writeEnd - readEnd
      lastFrameTotalDurationMillis = writeEnd - start
    }
  }

  private fun rawRgbaFrameLooksVisible(pixels: ByteArray, width: Int, height: Int): Boolean {
    if (width <= 0 || height <= 0 || pixels.size < width * height * 4) {
      return false
    }
    val left = (width * VISIBLE_REGION_LEFT_FRACTION).toInt().coerceIn(0, width - 1)
    val right = (width * VISIBLE_REGION_RIGHT_FRACTION).toInt().coerceIn(left + 1, width)
    val top = (height * VISIBLE_REGION_TOP_FRACTION).toInt().coerceIn(0, height - 1)
    val bottom = (height * VISIBLE_REGION_BOTTOM_FRACTION).toInt().coerceIn(top + 1, height)
    val xStride = ((right - left) / RAW_VISIBILITY_SAMPLE_COLUMNS).coerceAtLeast(1)
    val yStride = ((bottom - top) / RAW_VISIBILITY_SAMPLE_ROWS).coerceAtLeast(1)
    var sampled = 0
    var bright = 0
    var midOrBright = 0
    var luminanceSum = 0L
    var y = top
    while (y < bottom) {
      var x = left
      while (x < right) {
        val offset = (y * width + x) * 4
        if (offset + 2 < pixels.size) {
          val red = pixels[offset].toInt() and 0xff
          val green = pixels[offset + 1].toInt() and 0xff
          val blue = pixels[offset + 2].toInt() and 0xff
          val luminance = (red * 299 + green * 587 + blue * 114) / 1000
          luminanceSum += luminance.toLong()
          if (luminance >= RAW_VISIBILITY_BRIGHT_LUMINANCE) {
            bright += 1
          }
          if (luminance >= RAW_VISIBILITY_MID_LUMINANCE) {
            midOrBright += 1
          }
          sampled += 1
        }
        x += xStride
      }
      y += yStride
    }
    if (sampled == 0) {
      return false
    }
    val mean = luminanceSum.toDouble() / sampled.toDouble()
    val brightRatio = bright.toDouble() / sampled.toDouble()
    val midRatio = midOrBright.toDouble() / sampled.toDouble()
    return brightRatio >= RAW_VISIBILITY_MIN_BRIGHT_RATIO ||
      midRatio >= RAW_VISIBILITY_MIN_MID_RATIO ||
      mean >= RAW_VISIBILITY_MIN_MEAN_LUMINANCE
  }

  private fun readFfmpegOutput(stream: InputStream, parser: TicketH264AnnexBParser) {
    try {
      val input = BufferedInputStream(stream)
      val buffer = ByteArray(64 * 1024)
      while (wanted) {
        val read = input.read(buffer)
        if (read < 0) {
          break
        }
        if (read > 0) {
          parser.push(buffer.copyOf(read))
        }
      }
    } catch (error: IOException) {
      if (wanted) {
        throw error
      }
    }
  }

  private fun readStderrTail(process: Process) {
    scope.launch(Dispatchers.IO) {
      runCatching {
        process.errorStream.bufferedReader().forEachLine { line ->
          if (line.isNotBlank()) appendStderr(line)
        }
      }
    }
  }

  private fun appendStderr(line: String) {
    val joined = (stderrTail + "\n" + line).trim().takeLast(STDERR_TAIL_CHARS)
    stderrTail = joined
    Log.w(TAG, "ticket_ffmpeg_h264_stderr $line")
  }

  private fun updateEstimatedBitrate(bytes: Int, nowMillis: Long) {
    if (bitrateWindowStartedAtMillis == 0L || nowMillis - bitrateWindowStartedAtMillis > BITRATE_WINDOW_MILLIS) {
      bitrateWindowStartedAtMillis = nowMillis
      bitrateWindowBytes = 0L
    }
    bitrateWindowBytes += bytes.toLong()
    val elapsed = (nowMillis - bitrateWindowStartedAtMillis).coerceAtLeast(1L)
    estimatedBitrate = (bitrateWindowBytes * 8_000L) / elapsed
  }

  private suspend fun probeCaptureHelper(sourceWidth: Int, sourceHeight: Int) = rootExecutor.run(
    "${rootFeederCommand(sourceWidth, sourceHeight, targetFps = 1)} --frames 1 >/dev/null",
    timeout = 8.seconds
  )

  private fun ffmpegCommand(width: Int, height: Int, targetBitrate: Int, targetFps: Int): String {
    val halfBitrate = (targetBitrate / 2).coerceAtLeast(1_000_000)
    val keyInterval = targetFps.coerceAtLeast(1)
    return listOf(
      "chroot '${TicketScreenConfig.ROOT_FFMPEG_H264_CHROOT}'",
      "/usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
      "'${TicketScreenConfig.ROOT_FFMPEG_H264_BINARY}'",
      "-hide_banner -loglevel warning -nostats",
      "-f rawvideo -pix_fmt rgba -s ${width}x${height} -r $targetFps -i pipe:0",
      "-an -vf format=yuv420p",
      "-c:v libx264 -preset ultrafast -tune zerolatency",
      "-threads 1",
      "-x264-params keyint=$keyInterval:min-keyint=$keyInterval:scenecut=0:repeat-headers=1:bframes=0:sliced-threads=0",
      "-g $keyInterval -bf 0 -refs 1 -profile:v baseline -level 4.0",
      "-b:v $targetBitrate -maxrate $targetBitrate -bufsize $halfBitrate",
      "-pix_fmt yuv420p -f h264 pipe:1"
    ).joinToString(" ")
  }

  private fun rootFeederCommand(width: Int, height: Int, targetFps: Int): String {
    return listOf(
      "APK=${'$'}(pm path lv.jolkins.pixelorchestrator 2>/dev/null | sed -n 's/^package://p' | head -n 1)",
      "test -n \"${'$'}APK\"",
      "CLASSPATH=\"${'$'}APK\" app_process /system/bin lv.jolkins.pixelorchestrator.app.ticket.TicketRootSurfaceCaptureMain --width $width --height $height --fps $targetFps"
    ).joinToString(" && ")
  }

  private fun stopProcesses() {
    val ffmpeg = ffmpegProcess
    val feeder = feederProcess
    ffmpegProcess = null
    feederProcess = null
    runCatching { ffmpeg?.outputStream?.close() }
    runCatching { feeder?.inputStream?.close() }
    runCatching { feeder?.errorStream?.close() }
    runCatching { feeder?.destroy() }
    runCatching { ffmpeg?.destroy() }
    runCatching { feeder?.destroyForcibly() }
    runCatching { ffmpeg?.destroyForcibly() }
  }

  private fun clearFrameState() {
    width = null
    height = null
    bitrate = null
    fps = null
    frames = 0L
    keyFrames = 0L
    lastFrameBytes = 0
    lastFrameAtMillis = 0L
    lastStartAtMillis = 0L
    lastRootFrameReadDurationMillis = null
    lastFfmpegWriteDurationMillis = null
    lastFrameTotalDurationMillis = null
    suppressedRawFrames = 0L
    lastSuppressedRawFrameAtMillis = 0L
    firstVisibleRawFrameAtMillis = 0L
    lastRawFrameVisible = false
    droppedFrames = 0L
    estimatedBitrate = 0L
    bitrateWindowStartedAtMillis = 0L
    bitrateWindowBytes = 0L
  }

  private suspend fun cleanupMatchingProcesses() {
    rootExecutor.run(
      """
        for signal in TERM KILL; do
          ps -A -o PID,ARGS 2>/dev/null | while IFS= read -r line; do
            case "${'$'}line" in
              *"ps -A -o PID,ARGS"*|*"while IFS= read -r line"*) continue ;;
            esac
            case "${'$'}line" in
              *"ffmpeg"*" -f rawvideo "*|*"TicketRootSurfaceCaptureMain"*|*"while :; do screencap; sleep "*|*"do screencap; sleep "*)
                set -- ${'$'}line
                pid="${'$'}{1:-}"
                case "${'$'}pid" in
                  ''|*[!0-9]*) ;;
                  *) kill -"${'$'}signal" "${'$'}pid" >/dev/null 2>&1 || true ;;
                esac
                ;;
            esac
          done
          sleep 0.15
        done
      """.trimIndent(),
      timeout = 3.seconds
    )
  }

  private fun recordRestart(reason: String) {
    restartCount += 1
    lastExitReason = reason
    lastExitAtMillis = SystemClock.elapsedRealtime()
    Log.i(TAG, "ticket_ffmpeg_h264_restart reason=$reason restarts=$restartCount")
  }

  private fun publish() {
    onStateChanged(snapshot())
  }

  private fun ageMillis(timestampMillis: Long, nowMillis: Long): Long? {
    return timestampMillis.takeIf { it > 0L }?.let { (nowMillis - it).coerceAtLeast(0L) }
  }

  private fun InputStream.readFully(target: ByteArray, length: Int): Boolean {
    var offset = 0
    while (offset < length) {
      val read = read(target, offset, length - offset)
      if (read < 0) return false
      offset += read
    }
    return true
  }

  private fun ByteArray.readLittleEndianInt(offset: Int): Int {
    return (this[offset].toInt() and 0xff) or
      ((this[offset + 1].toInt() and 0xff) shl 8) or
      ((this[offset + 2].toInt() and 0xff) shl 16) or
      ((this[offset + 3].toInt() and 0xff) shl 24)
  }

  private companion object {
    private const val TAG = "TicketRootFfmpegH264"
    private const val RAW_FORMAT_RGBA_8888 = 1
    private const val RAW_FRAME_HEADER_BYTES = 12
    private const val RAW_FRAME_BYTES_BUFFER = 16 * 1024
    private const val RAW_VISIBILITY_SAMPLE_COLUMNS = 48
    private const val RAW_VISIBILITY_SAMPLE_ROWS = 72
    private const val RAW_VISIBILITY_BRIGHT_LUMINANCE = 90
    private const val RAW_VISIBILITY_MID_LUMINANCE = 42
    private const val RAW_VISIBILITY_MIN_BRIGHT_RATIO = 0.10
    private const val RAW_VISIBILITY_MIN_MID_RATIO = 0.22
    private const val RAW_VISIBILITY_MIN_MEAN_LUMINANCE = 48.0
    private const val VISIBLE_REGION_LEFT_FRACTION = 0.04
    private const val VISIBLE_REGION_RIGHT_FRACTION = 0.96
    private const val VISIBLE_REGION_TOP_FRACTION = 0.16
    private const val VISIBLE_REGION_BOTTOM_FRACTION = 0.82
    private const val RESTART_DELAY_MILLIS = 500L
    private const val STDERR_TAIL_CHARS = 1200
    private const val BITRATE_WINDOW_MILLIS = 3_000L
  }
}
