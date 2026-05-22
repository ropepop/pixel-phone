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
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.time.Duration.Companion.seconds

data class TicketControlCodeVisualProbe(
  val result: String,
  val reason: String,
  val atMillis: Long
)

class TicketRootHardwareH264CaptureEngine(
  private val scope: CoroutineScope,
  private val rootExecutor: RootExecutor,
  private val onFrame: (TicketRootCaptureFrame) -> Unit,
  private val onStateChanged: (TicketHardwareH264Health) -> Unit
) {
  private data class HardwareH264StartRequest(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val targetWidth: Int,
    val targetHeight: Int,
    val targetBitrate: Int,
    val targetFps: Int
  )

  private val startupLock = Any()
  private val keyFrameRequestLock = Any()
  @Volatile private var job: Job? = null
  @Volatile private var encoderProcess: Process? = null
  @Volatile private var wanted = false
  @Volatile private var available = false
  @Volatile private var state = "idle"
  @Volatile private var message = "Hardware H.264 capture is idle"
  @Volatile private var encoderName: String? = "MediaCodec video/avc"
  @Volatile private var width: Int? = null
  @Volatile private var height: Int? = null
  @Volatile private var bitrate: Int? = null
  @Volatile private var fps: Int? = null
  @Volatile private var frames = 0L
  @Volatile private var keyFrames = 0L
  @Volatile private var lastFrameBytes = 0
  @Volatile private var lastFrameAtMillis = 0L
  @Volatile private var lastStartAtMillis = 0L
  @Volatile private var lastFrameTotalDurationMillis: Long? = null
  @Volatile private var lastCaptureDurationMillis: Long? = null
  @Volatile private var lastDrawDurationMillis: Long? = null
  @Volatile private var lastEncodeDurationMillis: Long? = null
  @Volatile private var lastVisibilityCheckResult = "not_run"
  @Volatile private var blankFrameFailures = 0L
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
  @Volatile private var captureHelperMessage = "Root hardware H.264 helper has not been probed"
  @Volatile private var lastControlCodeVisualProbeResult = "not_run"
  @Volatile private var lastControlCodeVisualProbeReason = ""
  @Volatile private var lastControlCodeVisualProbeAtMillis = 0L
  @Volatile private var encoderProcessCount = 0
  @Volatile private var staleCaptureProcessCount = 0
  @Volatile private var lastCaptureCleanupResult = "not_run"
  @Volatile private var cleanStopReadyForNextStart = false
  @Volatile private var helperClasspath: String? = null
  @Volatile private var pendingKeyFrameReason: String? = null
  @Volatile private var desiredStartRequest: HardwareH264StartRequest? = null
  @Volatile private var activeStartRequest: HardwareH264StartRequest? = null
  @Volatile private var pendingCaptureRestartReason: String? = null

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
    helperClasspath = resolveHelperClasspath() ?: helperClasspath
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
        helper.stdout.trim().ifBlank { "Root hardware H.264 helper is unavailable" }
      }.takeLast(240)
      state = "unavailable"
      message = captureHelperMessage
      publish()
      return false
    }
    available = true
    captureHelperAvailable = true
    captureHelperState = if (sourceWidth != null && sourceHeight != null) "ready" else "installed"
    captureHelperMessage = if (sourceWidth != null && sourceHeight != null) {
      "Root hardware H.264 helper produced a test frame"
    } else {
      "app_process is available; hardware H.264 helper will be verified at stream start"
    }
    state = if (state == "unavailable") "idle" else state
    message = "Hardware H.264 capture is available"
    publish()
    return true
  }

  suspend fun captureSecurePngBase64(sourceWidth: Int, sourceHeight: Int): RootResult {
    helperClasspath = helperClasspath ?: resolveHelperClasspath()
    return rootExecutor.run(
      rootSecurePngCommand(sourceWidth, sourceHeight),
      timeout = 6.seconds
    )
  }

  fun start(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int, targetBitrate: Int, targetFps: Int) {
    val request = HardwareH264StartRequest(
      sourceWidth = sourceWidth,
      sourceHeight = sourceHeight,
      targetWidth = targetWidth,
      targetHeight = targetHeight,
      targetBitrate = targetBitrate,
      targetFps = targetFps
    )
    synchronized(startupLock) {
      val previousRequest = desiredStartRequest
      desiredStartRequest = request
      width = targetWidth
      height = targetHeight
      bitrate = targetBitrate
      fps = targetFps
      wanted = true
      if (job?.isActive == true) {
        if (previousRequest != null && previousRequest != request) {
          requestCaptureRestartLocked("capture_config_changed")
        } else {
          publish()
        }
        return
      }
      job = scope.launch(Dispatchers.IO) {
        runCaptureLoop()
      }
    }
  }

  fun restart(reason: String) {
    synchronized(startupLock) {
      if (job?.isActive == true && desiredStartRequest != null) {
        requestCaptureRestartLocked("requested_restart:$reason")
        return
      }
      requestKeyFrame("restart_without_active_encoder:$reason")
    }
  }

  private fun requestCaptureRestartLocked(reason: String) {
    pendingCaptureRestartReason = reason
    cleanStopReadyForNextStart = false
    state = "restarting"
    message = if (reason == "capture_config_changed") {
      "Hardware H.264 capture config changed; restarting"
    } else {
      "Hardware H.264 capture restart requested"
    }
    Log.i(TAG, "ticket_root_hardware_h264_capture_config_changed reason=$reason active=$activeStartRequest desired=$desiredStartRequest")
    publish()
    stopProcesses()
  }

  fun stop(reason: String = "stopped") {
    wanted = false
    desiredStartRequest = null
    activeStartRequest = null
    pendingCaptureRestartReason = null
    job?.cancel()
    job = null
    stopProcesses()
    scope.launch(Dispatchers.IO + NonCancellable) {
      cleanupMatchingProcesses()
    }
    state = "idle"
    message = if (available) "Hardware H.264 capture is available" else "Hardware H.264 capture stopped: $reason"
    clearFrameState()
    publish()
  }

  fun requestKeyFrame(reason: String) {
    Log.i(TAG, "ticket_hardware_h264_keyframe_requested reason=$reason")
    if (!writeHardwareKeyFrameRequest(reason)) {
      synchronized(keyFrameRequestLock) {
        pendingKeyFrameReason = reason
      }
    }
  }

  fun requestBurst(reason: String) {
    Log.i(TAG, "ticket_hardware_h264_burst_requested reason=$reason")
    writeHardwareCommand("burst\n", "burst", reason)
  }

  fun requestControlCodeVisualProbe(reason: String) {
    Log.i(TAG, "ticket_hardware_h264_control_code_visual_probe_requested reason=$reason")
    lastControlCodeVisualProbeResult = "requested"
    lastControlCodeVisualProbeReason = reason
    writeHardwareCommand("control_code_visual_probe\n", "control_code_visual_probe", reason)
  }

  fun recentControlCodeVisualProbeAfter(startedAtMillis: Long): TicketControlCodeVisualProbe? {
    val atMillis = lastControlCodeVisualProbeAtMillis
    val result = lastControlCodeVisualProbeResult
    if (atMillis < startedAtMillis || result == "not_run" || result == "requested") {
      return null
    }
    return TicketControlCodeVisualProbe(
      result = result,
      reason = lastControlCodeVisualProbeReason,
      atMillis = atMillis
    )
  }

  private fun writeHardwareKeyFrameRequest(reason: String): Boolean {
    return writeHardwareCommand("keyframe\n", "keyframe", reason)
  }

  private fun writeHardwareCommand(command: String, label: String, reason: String): Boolean {
    val process = encoderProcess
    val outputStream = encoderProcess?.outputStream
    if (process == null || !process.isAlive || outputStream == null) {
      Log.w(TAG, "ticket_hardware_h264_${label}_skipped reason=$reason state=$state")
      return false
    }
    return try {
      synchronized(keyFrameRequestLock) {
        outputStream.write(command.toByteArray(Charsets.UTF_8))
        outputStream.flush()
      }
      true
    } catch (error: IOException) {
      Log.w(TAG, "ticket_hardware_h264_${label}_failed reason=$reason", error)
      false
    }
  }

  private fun flushPendingKeyFrameRequest(reason: String) {
    val pending = synchronized(keyFrameRequestLock) {
      val value = pendingKeyFrameReason
      pendingKeyFrameReason = null
      value
    } ?: return
    Log.i(TAG, "ticket_hardware_h264_pending_keyframe_flush reason=$reason pending=$pending")
    if (!writeHardwareKeyFrameRequest("$reason:$pending")) {
      synchronized(keyFrameRequestLock) {
        pendingKeyFrameReason = pending
      }
    }
  }

  suspend fun cleanupStaleProcesses() {
    stopProcesses()
    cleanupMatchingProcesses()
    clearFrameState()
    state = if (available) "idle" else state
    message = if (available) "Hardware H.264 capture is available" else message
    publish()
  }

  fun snapshot(nowMillis: Long = SystemClock.elapsedRealtime()): TicketHardwareH264Health {
    return TicketHardwareH264Health(
      available = available,
      active = job?.isActive == true && encoderProcess?.isAlive == true,
      encoderName = encoderName,
      captureSource = TicketScreenConfig.ROOT_HARDWARE_H264_CAPTURE_SOURCE,
      captureMethod = TicketScreenConfig.ROOT_HARDWARE_H264_CAPTURE_METHOD,
      captureHelperAvailable = captureHelperAvailable,
      captureHelperState = captureHelperState,
      captureHelperMessage = captureHelperMessage,
      state = state,
      message = message,
      width = width,
      height = height,
      bitrate = bitrate,
      fps = fps,
      colorCorrection = TicketScreenConfig.ROOT_HARDWARE_H264_COLOR_CORRECTION,
      colorStandard = TicketScreenConfig.ROOT_HARDWARE_H264_COLOR_STANDARD,
      frames = frames,
      keyFrames = keyFrames,
      lastFrameBytes = lastFrameBytes,
      estimatedBitrate = estimatedBitrate,
      lastFrameAgoMillis = ageMillis(lastFrameAtMillis, nowMillis),
      lastStartAgoMillis = ageMillis(lastStartAtMillis, nowMillis),
      lastFrameTotalDurationMillis = lastFrameTotalDurationMillis,
      secureLayerCaptureEnabled = true,
      protectedContentCaptureEnabled = true,
      lastCaptureDurationMillis = lastCaptureDurationMillis,
      lastDrawDurationMillis = lastDrawDurationMillis,
      lastEncodeDurationMillis = lastEncodeDurationMillis,
      lastVisibilityCheckResult = lastVisibilityCheckResult,
      blankFrameFailures = blankFrameFailures,
      encoderProcessCount = encoderProcessCount,
      staleCaptureProcessCount = staleCaptureProcessCount,
      lastCaptureCleanupResult = lastCaptureCleanupResult,
      droppedFrames = droppedFrames,
      restartCount = restartCount,
      lastExitReason = lastExitReason,
      lastExitAgoMillis = ageMillis(lastExitAtMillis, nowMillis),
      stderrTail = stderrTail
    )
  }

  private suspend fun runCaptureLoop() {
    while (wanted) {
      val request = desiredStartRequest ?: break
      activeStartRequest = request
      val parser = TicketH264AnnexBParser { payload, keyFrame ->
        val now = SystemClock.elapsedRealtime()
        frames += 1
        lastFrameAtMillis = now
        lastFrameBytes = payload.size
        lastFrameTotalDurationMillis = 0L
        updateEstimatedBitrate(payload.size, now)
        if (keyFrame) {
          keyFrames += 1
        }
        onFrame(
          TicketRootCaptureFrame(
            keyFrame = keyFrame,
            timestampUs = SystemClock.elapsedRealtimeNanos() / 1_000L,
            payload = payload,
            width = request.targetWidth,
            height = request.targetHeight
          )
        )
      }
      try {
        consumeCleanStopForFastStart()
        state = "starting"
        message = "Hardware H.264 capture is starting"
        lastStartAtMillis = SystemClock.elapsedRealtime()
        publish()
        helperClasspath = helperClasspath ?: resolveHelperClasspath()
        val localEncoder = ProcessBuilder(
          "su",
          "-c",
          rootEncoderCommand(
            request.sourceWidth,
            request.sourceHeight,
            request.targetWidth,
            request.targetHeight,
            request.targetBitrate,
            request.targetFps
          )
        )
          .redirectErrorStream(false)
          .start()
        encoderProcess = localEncoder
        flushPendingKeyFrameRequest("encoder_started")
        readStderrTail(localEncoder)
        schedulePostStartProcessSanityCheck()
        state = "active"
        message = "Hardware H.264 capture is active"
        publish()
        readEncoderOutput(localEncoder.inputStream, parser)
        parser.finish()
        val exit = withContext(Dispatchers.IO) { runCatching { localEncoder.waitFor() }.getOrDefault(-1) }
        if (wanted) {
          val requestedRestart = consumePendingCaptureRestartReason()
          if (requestedRestart != null) {
            recordRestart(requestedRestart)
            state = "restarting"
            message = restartMessage(requestedRestart)
            publish()
          } else {
            recordRestart("hardware_encoder_exit_$exit")
            state = "restarting"
            message = "Hardware H.264 encoder exited with code $exit; restarting"
            publish()
            delay(RESTART_DELAY_MILLIS)
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        if (wanted) {
          val requestedRestart = consumePendingCaptureRestartReason()
          if (requestedRestart != null) {
            recordRestart(requestedRestart)
            state = "restarting"
            message = restartMessage(requestedRestart)
            publish()
          } else {
            recordRestart("failure")
            state = "restarting"
            message = "Hardware H.264 capture failed: ${error.message ?: error::class.java.simpleName}"
            Log.w(TAG, "ticket_hardware_h264_capture_failed", error)
            publish()
            delay(RESTART_DELAY_MILLIS)
          }
        }
      } finally {
        withContext(NonCancellable) {
          stopProcesses()
          cleanupMatchingProcesses()
        }
        activeStartRequest = null
      }
    }
    state = "idle"
    message = "Hardware H.264 capture is idle"
    publish()
  }

  private fun consumePendingCaptureRestartReason(): String? {
    return synchronized(startupLock) {
      val reason = pendingCaptureRestartReason
      pendingCaptureRestartReason = null
      reason
    }
  }

  private fun restartMessage(reason: String): String {
    return if (reason == "capture_config_changed") {
      "Hardware H.264 capture config changed; restarting"
    } else {
      "Hardware H.264 capture restart requested"
    }
  }

  private fun readEncoderOutput(stream: InputStream, parser: TicketH264AnnexBParser) {
    try {
      val input = BufferedInputStream(stream)
      val buffer = ByteArray(64 * 1024)
      while (wanted) {
        val startedAtMillis = SystemClock.elapsedRealtime()
        val read = input.read(buffer)
        if (read < 0) {
          break
        }
        if (read > 0) {
          parser.push(buffer.copyOf(read))
          lastFrameTotalDurationMillis = SystemClock.elapsedRealtime() - startedAtMillis
        }
      }
    } catch (error: IOException) {
      if (wanted) {
        throw error
      }
    }
  }

  private suspend fun probeCaptureHelper(sourceWidth: Int, sourceHeight: Int): RootResult {
    val size = TicketStreamSizing.rootHardwareH264(sourceWidth, sourceHeight)
    return rootExecutor.run(
      "${rootEncoderCommand(sourceWidth, sourceHeight, size.width, size.height, TicketScreenConfig.ROOT_HARDWARE_H264_BITRATE, targetFps = 1)} --frames 1 >/dev/null",
      timeout = 8.seconds
    )
  }

  private suspend fun resolveHelperClasspath(): String? {
    val result = rootExecutor.run(
      "pm path lv.jolkins.pixelorchestrator 2>/dev/null | sed -n 's/^package://p' | head -n 1",
      timeout = 2.seconds
    )
    return result.stdout.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim()
  }

  private fun rootEncoderCommand(
    sourceWidth: Int,
    sourceHeight: Int,
    width: Int,
    height: Int,
    targetBitrate: Int,
    targetFps: Int
  ): String {
    val commonArgs = "--source-width $sourceWidth --source-height $sourceHeight --width $width --height $height --crop-top-source ${TicketScreenConfig.TICKET_MEDIA_TOP_CROP_SOURCE_PIXELS} --fps $targetFps --steady-fps ${TicketScreenConfig.ROOT_HARDWARE_H264_STEADY_FPS} --burst-hold-millis ${TicketScreenConfig.ROOT_HARDWARE_H264_BURST_HOLD_MILLIS} --bitrate $targetBitrate --keyframe-interval-millis ${TicketScreenConfig.ROOT_HARDWARE_H264_KEYFRAME_INTERVAL_MILLIS}"
    return rootCaptureHelperCommand(commonArgs)
  }

  private fun rootSecurePngCommand(sourceWidth: Int, sourceHeight: Int): String {
    val commonArgs = "--png-base64 --source-width $sourceWidth --source-height $sourceHeight --width $sourceWidth --height $sourceHeight"
    return rootCaptureHelperCommand(commonArgs)
  }

  private fun rootCaptureHelperCommand(commonArgs: String): String {
    val cachedClasspath = helperClasspath?.takeIf { it.isNotBlank() }?.let(::shellQuote)
    if (cachedClasspath != null) {
      return "CLASSPATH=$cachedClasspath app_process /system/bin lv.jolkins.pixelorchestrator.app.ticket.TicketRootHardwareH264CaptureMain $commonArgs"
    }
    return listOf(
      "APK=${'$'}(pm path lv.jolkins.pixelorchestrator 2>/dev/null | sed -n 's/^package://p' | head -n 1)",
      "test -n \"${'$'}APK\"",
      "CLASSPATH=\"${'$'}APK\" app_process /system/bin lv.jolkins.pixelorchestrator.app.ticket.TicketRootHardwareH264CaptureMain $commonArgs"
    ).joinToString(" && ")
  }

  private fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
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
    parseHelperDiagnostic(line)
    stderrTail = (stderrTail + "\n" + line).trim().takeLast(STDERR_TAIL_CHARS)
    Log.w(TAG, "ticket_hardware_h264_stderr $line")
  }

  private fun parseHelperDiagnostic(line: String) {
    when {
      line.startsWith("METRIC ") -> {
        val fields = diagnosticFields(line)
        lastCaptureDurationMillis = fields["capture_ms"]?.toLongOrNull()
        lastDrawDurationMillis = fields["draw_ms"]?.toLongOrNull()
        lastEncodeDurationMillis = fields["encode_ms"]?.toLongOrNull()
        fields["fps_target"]?.toIntOrNull()?.let { fps = it }
        fields["visibility"]?.takeIf { it.isNotBlank() }?.let { lastVisibilityCheckResult = it }
      }
      line.startsWith("VISIBILITY ") -> {
        val fields = diagnosticFields(line)
        lastVisibilityCheckResult = fields["result"].orEmpty().ifBlank { "unknown" }
        if (fields["failure"] == "true") {
          blankFrameFailures += 1
        }
      }
      line.startsWith("CONTROL_CODE_VISUAL ") -> {
        val fields = diagnosticFields(line)
        lastControlCodeVisualProbeResult = fields["result"].orEmpty().ifBlank { "unknown" }
        lastControlCodeVisualProbeReason = fields["reason"].orEmpty()
        lastControlCodeVisualProbeAtMillis = SystemClock.elapsedRealtime()
      }
    }
  }

  private fun diagnosticFields(line: String): Map<String, String> {
    return line.substringAfter(' ', "")
      .split(' ')
      .mapNotNull { part ->
        val index = part.indexOf('=')
        if (index <= 0) {
          null
        } else {
          part.substring(0, index) to part.substring(index + 1)
        }
      }
      .toMap()
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

  private fun stopProcesses() {
    val encoder = encoderProcess
    encoderProcess = null
    runCatching { encoder?.inputStream?.close() }
    runCatching { encoder?.errorStream?.close() }
    runCatching { encoder?.destroy() }
    runCatching { encoder?.destroyForcibly() }
    cleanupMatchingProcessesDirect("stop_processes")
  }

  private fun consumeCleanStopForFastStart(): Boolean {
    if (!cleanStopReadyForNextStart) {
      return false
    }
    cleanStopReadyForNextStart = false
    val counts = HardwareProcessCounts(encoderProcessCount = 0, wrapperProcessCount = 0)
    updateProcessCounts(counts, expectedEncoderProcesses = 0)
    lastCaptureCleanupResult = "start_cleanup_reused_clean_stop:${counts.summary()}"
    Log.i(TAG, "ticket_hardware_h264_fast_start_reused_clean_stop")
    return true
  }

  private fun clearFrameState() {
    desiredStartRequest = null
    activeStartRequest = null
    pendingCaptureRestartReason = null
    width = null
    height = null
    bitrate = null
    fps = null
    frames = 0L
    keyFrames = 0L
    lastFrameBytes = 0
    lastFrameAtMillis = 0L
    lastStartAtMillis = 0L
    lastFrameTotalDurationMillis = null
    lastCaptureDurationMillis = null
    lastDrawDurationMillis = null
    lastEncodeDurationMillis = null
    lastVisibilityCheckResult = "not_run"
    blankFrameFailures = 0L
    droppedFrames = 0L
    estimatedBitrate = 0L
    bitrateWindowStartedAtMillis = 0L
    bitrateWindowBytes = 0L
  }

  private suspend fun reconcileStaleCaptureProcessesBeforeStart(): HardwareProcessCounts {
    val before = countMatchingProcesses()
    updateProcessCounts(before, expectedEncoderProcesses = 0)
    if (before.encoderProcessCount == 0) {
      lastCaptureCleanupResult = "start_cleanup_ok:${before.summary()}"
      return before
    }
    cleanupMatchingProcesses()
    val after = countMatchingProcesses()
    updateProcessCounts(after, expectedEncoderProcesses = 0)
    lastCaptureCleanupResult = if (after.encoderProcessCount == 0) {
      "start_cleanup_ok:before=${before.summary()} after=${after.summary()}"
    } else {
      "capture_start_refused_stale_processes:before=${before.summary()} after=${after.summary()}"
    }
    return after
  }

  private fun schedulePostStartProcessSanityCheck() {
    scope.launch(Dispatchers.IO) {
      delay(POST_START_PROCESS_SANITY_DELAY_MILLIS)
      if (!wanted) {
        return@launch
      }
      val counts = countMatchingProcesses()
      updateProcessCounts(counts, expectedEncoderProcesses = 1)
      lastCaptureCleanupResult = if (counts.encoderProcessCount <= 1) {
        "post_start_sanity_ok:${counts.summary()}"
      } else {
        "post_start_sanity_stale_processes:${counts.summary()}"
      }
      publish()
    }
  }

  private suspend fun cleanupMatchingProcesses(): HardwareProcessCounts {
    val before = countMatchingProcesses()
    rootExecutor.run(cleanupMatchingProcessesCommand(), timeout = 3.seconds)
    val after = countMatchingProcesses()
    updateProcessCounts(after, expectedEncoderProcesses = 0)
    lastCaptureCleanupResult = if (after.encoderProcessCount == 0) {
      if (!wanted) {
        cleanStopReadyForNextStart = true
      }
      "cleanup_ok:before=${before.summary()} after=${after.summary()}"
    } else {
      cleanStopReadyForNextStart = false
      "cleanup_leftovers:before=${before.summary()} after=${after.summary()}"
    }
    return after
  }

  private fun cleanupMatchingProcessesDirect(reason: String) {
    runCatching {
      val process = ProcessBuilder("su", "-c", cleanupMatchingProcessesCommand())
        .redirectErrorStream(true)
        .start()
      if (!process.waitFor(DIRECT_CLEANUP_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
        process.destroyForcibly()
        lastCaptureCleanupResult = "direct_cleanup_timeout:$reason"
      }
    }.onFailure { error ->
      lastCaptureCleanupResult = "direct_cleanup_failed:$reason:${error.message ?: error::class.java.simpleName}"
    }
  }

  private fun cleanupMatchingProcessesCommand(): String {
    return """
        for signal in TERM KILL; do
          ps -A -o PID,ARGS 2>/dev/null | while IFS= read -r line; do
            case "${'$'}line" in
              *"ps -A -o PID,ARGS"*|*"while IFS= read -r line"*|*"awk '"*|*"com.android.commands.content.Content"*|*"content://"*"--method log"*) continue ;;
            esac
            case "${'$'}line" in
              *"app_process"*"TicketRootHardwareH264CaptureMain"*|*"sh -c"*"TicketRootHardwareH264CaptureMain"*|*"TicketRootHardwareH264CaptureMain"*)
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
      """.trimIndent()
  }

  private suspend fun countMatchingProcesses(): HardwareProcessCounts {
    val result = rootExecutor.run(
      """
        ps -A -o PID,ARGS 2>/dev/null | awk '
          /ps -A -o PID,ARGS/ { next }
          /awk/ { next }
          /grep/ { next }
          {
            line = ${'$'}0
            is_probe = index(line, "--frames 1") > 0
            is_root_command_log = index(line, "com.android.commands.content.Content") > 0 || (index(line, "content://") > 0 && index(line, "--method log") > 0)
            is_encoder = index(line, "TicketRootHardwareH264CaptureMain") > 0 && !is_probe && !is_root_command_log
            is_wrapper = index(line, "sh -c") > 0 || index(line, "su -c") > 0 || index(line, "/system/bin/sh") > 0
            if (is_encoder && index(line, "app_process") > 0 && !is_wrapper) {
              encoder += 1
            } else if (is_encoder) {
              wrappers += 1
            }
          }
          END {
            printf("encoder=%d wrappers=%d\n", encoder + 0, wrappers + 0)
          }
        '
      """.trimIndent(),
      timeout = 2.seconds
    )
    return parseProcessCounts(result.stdout)
  }

  private fun parseProcessCounts(stdout: String): HardwareProcessCounts {
    val values = stdout.trim().split(Regex("\\s+"))
      .mapNotNull { part ->
        val key = part.substringBefore("=", missingDelimiterValue = "")
        val value = part.substringAfter("=", missingDelimiterValue = "").toIntOrNull()
        if (key.isBlank() || value == null) null else key to value
      }
      .toMap()
    return HardwareProcessCounts(
      encoderProcessCount = values["encoder"] ?: 0,
      wrapperProcessCount = values["wrappers"] ?: 0
    )
  }

  private fun updateProcessCounts(counts: HardwareProcessCounts, expectedEncoderProcesses: Int) {
    encoderProcessCount = counts.encoderProcessCount
    staleCaptureProcessCount =
      (counts.encoderProcessCount - expectedEncoderProcesses).coerceAtLeast(0) +
        if (expectedEncoderProcesses == 0) counts.wrapperProcessCount else 0
  }

  private data class HardwareProcessCounts(
    val encoderProcessCount: Int,
    val wrapperProcessCount: Int
  ) {
    fun summary(): String {
      return "encoder=$encoderProcessCount wrappers=$wrapperProcessCount"
    }
  }

  private fun recordRestart(reason: String) {
    restartCount += 1
    lastExitReason = reason
    lastExitAtMillis = SystemClock.elapsedRealtime()
    Log.i(TAG, "ticket_hardware_h264_restart reason=$reason restarts=$restartCount")
  }

  private fun publish() {
    onStateChanged(snapshot())
  }

  private fun ageMillis(timestampMillis: Long, nowMillis: Long): Long? {
    return timestampMillis.takeIf { it > 0L }?.let { (nowMillis - it).coerceAtLeast(0L) }
  }

  private companion object {
    private const val TAG = "TicketHardwareH264"
    private const val RESTART_DELAY_MILLIS = 600L
    private const val BITRATE_WINDOW_MILLIS = 1_000L
    private const val STDERR_TAIL_CHARS = 4_000
    private const val DIRECT_CLEANUP_WAIT_MILLIS = 700L
    private const val POST_START_PROCESS_SANITY_DELAY_MILLIS = 250L
  }
}
