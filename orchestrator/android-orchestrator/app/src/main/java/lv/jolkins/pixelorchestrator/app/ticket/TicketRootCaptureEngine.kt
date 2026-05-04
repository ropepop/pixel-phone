package lv.jolkins.pixelorchestrator.app.ticket

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lv.jolkins.pixelorchestrator.rootexec.RootExecutor
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

data class TicketRootCaptureFrame(
  val keyFrame: Boolean,
  val timestampUs: Long,
  val payload: ByteArray,
  val width: Int,
  val height: Int
)

class TicketRootCaptureEngine(
  private val scope: CoroutineScope,
  private val rootExecutor: RootExecutor,
  private val onFrame: (TicketRootCaptureFrame) -> Unit,
  private val onStateChanged: (TicketRootCaptureHealth) -> Unit
) {
  @Volatile private var job: Job? = null
  @Volatile private var process: Process? = null
  @Volatile private var wanted = AtomicBoolean(false)
  @Volatile private var supported = false
  @Volatile private var state = "idle"
  @Volatile private var message = "Root capture is idle"
  @Volatile private var width: Int? = null
  @Volatile private var height: Int? = null
  @Volatile private var bitrate: Int? = null
  @Volatile private var commandNeedle: String = ""
  @Volatile private var frames = 0L
  @Volatile private var keyFrames = 0L
  @Volatile private var restarts = 0L
  @Volatile private var suppressedRestartRequests = 0L
  @Volatile private var lastFrameAtMillis = 0L
  @Volatile private var lastKeyFrameAtMillis = 0L
  @Volatile private var lastStartAtMillis = 0L
  @Volatile private var lastRestartAtMillis = 0L
  @Volatile private var lastRestartReason: String? = null
  @Volatile private var expectedRestartStop = false
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
    val result = rootExecutor.run("command -v screenrecord >/dev/null 2>&1", timeout = 5.seconds)
    supported = result.ok
    state = if (supported) state else "unavailable"
    message = if (supported) {
      "Root screenrecord capture is available"
    } else {
      "screenrecord is unavailable to root"
    }
    publish()
    return supported
  }

  fun start(sourceWidth: Int, sourceHeight: Int) {
    val target = targetSize(sourceWidth, sourceHeight)
    width = target.first
    height = target.second
    bitrate = TicketScreenConfig.ROOT_CAPTURE_BITRATE
    commandNeedle = captureCommand(target.first, target.second, TicketScreenConfig.ROOT_CAPTURE_BITRATE)
    wanted.set(true)
    if (job?.isActive == true) {
      publish()
      return
    }
    job = scope.launch(Dispatchers.IO) {
      runCaptureLoop(target.first, target.second, TicketScreenConfig.ROOT_CAPTURE_BITRATE)
    }
  }

  fun stop(reason: String = "stopped") {
    wanted.set(false)
    expectedRestartStop = false
    job?.cancel()
    job = null
    stopProcess()
    state = "idle"
    message = "Root capture stopped: $reason"
    publish()
  }

  fun restart(reason: String) {
    if (!wanted.get()) {
      return
    }
    recordRestart(reason)
    expectedRestartStop = true
    message = "Root capture restarting: $reason"
    stopProcess(killChildren = true)
    publish()
  }

  fun noteSuppressedRestartRequest(reason: String) {
    suppressedRestartRequests += 1
    Log.i(TAG, "ticket_root_capture_restart_suppressed reason=$reason")
    publish()
  }

  suspend fun cleanupStaleProcesses() {
    cleanupMatchingScreenrecord()
  }

  fun snapshot(nowMillis: Long = SystemClock.elapsedRealtime()): TicketRootCaptureHealth {
    return TicketRootCaptureHealth(
      supported = supported,
      active = job?.isActive == true && process?.isAlive == true,
      state = state,
      message = message,
      width = width,
      height = height,
      bitrate = bitrate,
      frames = frames,
      keyFrames = keyFrames,
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

  private suspend fun runCaptureLoop(width: Int, height: Int, bitrate: Int) {
    while (wanted.get()) {
      val parser = TicketH264AnnexBParser { payload, keyFrame ->
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
            timestampUs = SystemClock.elapsedRealtimeNanos() / 1_000L,
            payload = payload,
            width = width,
            height = height
          )
        )
      }
      val command = captureCommand(width, height, bitrate)
      commandNeedle = command
      try {
        state = "starting"
        message = "Root capture is starting"
        publish()
        lastStartAtMillis = SystemClock.elapsedRealtime()
        val localProcess = ProcessBuilder("su", "-c", command)
          .redirectErrorStream(false)
          .start()
        process = localProcess
        state = "active"
        message = "Root capture is active"
        publish()
        logProcessStderr(localProcess)
        val buffer = ByteArray(64 * 1024)
        val input = BufferedInputStream(localProcess.inputStream)
        while (wanted.get()) {
          val read = input.read(buffer)
          if (read < 0) {
            break
          }
          if (read > 0) {
            parser.push(buffer.copyOf(read))
          }
        }
        parser.finish()
        val exitCode = runCatching { localProcess.waitFor() }.getOrDefault(-1)
        if (wanted.get()) {
          if (expectedRestartStop) {
            expectedRestartStop = false
          } else {
            recordRestart("process_exit_$exitCode")
          }
          state = "restarting"
          message = "Root capture exited with code $exitCode; restarting"
          publish()
          delay(ROOT_CAPTURE_RESTART_DELAY_MILLIS)
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        if (isExpectedRootCaptureClose(error)) {
          if (wanted.get()) {
            if (expectedRestartStop) {
              expectedRestartStop = false
            }
            state = "restarting"
            message = "Root capture stream closed during restart; restarting"
            Log.i(TAG, "ticket_root_capture_expected_close_during_restart")
            publish()
            delay(ROOT_CAPTURE_RESTART_DELAY_MILLIS)
          }
        } else if (wanted.get()) {
          recordRestart("failure")
          state = "restarting"
          message = "Root capture failed: ${error.message ?: error::class.java.simpleName}"
          Log.w(TAG, "ticket_root_capture_failed", error)
          publish()
          delay(ROOT_CAPTURE_RESTART_DELAY_MILLIS)
        }
      } finally {
        stopProcess(killChildren = false)
      }
    }
    state = "idle"
    message = "Root capture is idle"
    publish()
  }

  private fun stopProcess(killChildren: Boolean = true) {
    val local = process
    process = null
    if (local != null) {
      runCatching { local.destroy() }
      runCatching { local.destroyForcibly() }
    }
    if (!killChildren) {
      return
    }
    scope.launch(Dispatchers.IO) {
      cleanupMatchingScreenrecord()
    }
  }

  private fun recordRestart(reason: String) {
    restarts += 1
    lastRestartAtMillis = SystemClock.elapsedRealtime()
    lastRestartReason = reason
    synchronized(restartReasonCounts) {
      restartReasonCounts[reason] = (restartReasonCounts[reason] ?: 0L) + 1L
    }
    Log.i(TAG, "ticket_root_capture_restart reason=$reason restarts=$restarts")
  }

  private suspend fun cleanupMatchingScreenrecord() {
    val needle = commandNeedle
    if (needle.isBlank()) {
      return
    }
    val escapedNeedle = needle.replace("'", "'\"'\"'")
    rootExecutor.run(
      """
        needle='${escapedNeedle}'
        for signal in TERM KILL; do
          ps -A -o PID,ARGS 2>/dev/null | while IFS= read -r line; do
            case "${'$'}line" in
              *"${'$'}needle"*)
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

  private fun logProcessStderr(localProcess: Process) {
    scope.launch(Dispatchers.IO) {
      try {
        val stderr = readRootCaptureStderr(localProcess.errorStream)
        if (!stderr.isNullOrBlank()) {
          Log.w(TAG, "ticket_root_capture_stderr $stderr")
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        Log.d(TAG, "ticket_root_capture_stderr_reader_closed", error)
      }
    }
  }

  private fun targetSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
    val targetWidth = TicketScreenConfig.ROOT_CAPTURE_WIDTH.evenAtLeastTwo()
    val targetHeight = ((sourceHeight / sourceWidth.toFloat()) * targetWidth)
      .roundToInt()
      .evenAtLeastTwo()
    return targetWidth to targetHeight
  }

  private fun captureCommand(width: Int, height: Int, bitrate: Int): String {
    return listOf(
      "screenrecord",
      "--output-format=h264",
      "--size ${width}x${height}",
      "--bit-rate $bitrate",
      "--time-limit 180",
      "-"
    ).joinToString(" ")
  }

  private fun publish() {
    onStateChanged(snapshot())
  }

  private fun ageMillis(timestampMillis: Long, nowMillis: Long): Long? {
    return timestampMillis.takeIf { it > 0L }?.let { (nowMillis - it).coerceAtLeast(0L) }
  }

  private fun Int.evenAtLeastTwo(): Int {
    val atLeastTwo = coerceAtLeast(2)
    return if (atLeastTwo % 2 == 0) atLeastTwo else atLeastTwo - 1
  }

  private companion object {
    private const val TAG = "TicketRootCapture"
    private const val ROOT_CAPTURE_RESTART_DELAY_MILLIS = 500L
  }
}

internal fun readRootCaptureStderr(stream: InputStream): String? {
  return try {
    stream.bufferedReader().use { it.readText() }.trim().ifBlank { null }
  } catch (_: IOException) {
    null
  }
}

internal fun isExpectedRootCaptureClose(error: Throwable): Boolean {
  if (error !is IOException) {
    return false
  }
  val message = error.message.orEmpty()
  return message.contains("Stream closed", ignoreCase = true) ||
    message.contains("read interrupted by close", ignoreCase = true)
}

class TicketH264AnnexBParser(
  private val onAccessUnit: (payload: ByteArray, keyFrame: Boolean) -> Unit
) {
  private var buffer = ByteArray(0)
  private var sps: ByteArray? = null
  private var pps: ByteArray? = null

  fun push(bytes: ByteArray) {
    if (bytes.isEmpty()) {
      return
    }
    buffer += bytes
    drain(keepTrailing = true)
  }

  fun finish() {
    drain(keepTrailing = false)
    buffer = ByteArray(0)
  }

  private fun drain(keepTrailing: Boolean) {
    while (true) {
      val first = findStartCode(buffer, 0)
      if (first == null) {
        if (buffer.size > MAX_BUFFER_BYTES) {
          buffer = buffer.takeLast(TRAILING_SEARCH_BYTES).toByteArray()
        }
        return
      }
      if (first.index > 0) {
        buffer = buffer.copyOfRange(first.index, buffer.size)
      }
      val next = findStartCode(buffer, first.length)
      if (next == null) {
        if (!keepTrailing) {
          processNal(buffer)
        }
        return
      }
      val nal = buffer.copyOfRange(0, next.index)
      buffer = buffer.copyOfRange(next.index, buffer.size)
      processNal(nal)
    }
  }

  private fun processNal(nalWithStartCode: ByteArray) {
    val start = startCodeLengthAt(nalWithStartCode, 0) ?: return
    if (nalWithStartCode.size <= start) {
      return
    }
    when (val nalType = nalWithStartCode[start].toInt() and 0x1f) {
      NAL_SPS -> sps = nalWithStartCode.copyOf()
      NAL_PPS -> pps = nalWithStartCode.copyOf()
      NAL_IDR -> onAccessUnit(prependParameterSets(nalWithStartCode), true)
      NAL_NON_IDR -> onAccessUnit(nalWithStartCode.copyOf(), false)
      else -> if (nalType in 1..5) {
        onAccessUnit(nalWithStartCode.copyOf(), nalType == NAL_IDR)
      }
    }
  }

  private fun prependParameterSets(nal: ByteArray): ByteArray {
    val output = ByteArrayOutputStream()
    sps?.let { output.write(it) }
    pps?.let { output.write(it) }
    output.write(nal)
    return output.toByteArray()
  }

  private fun findStartCode(bytes: ByteArray, from: Int): StartCode? {
    var index = from.coerceAtLeast(0)
    while (index <= bytes.size - 3) {
      val length = startCodeLengthAt(bytes, index)
      if (length != null) {
        return StartCode(index, length)
      }
      index += 1
    }
    return null
  }

  private fun startCodeLengthAt(bytes: ByteArray, index: Int): Int? {
    if (index + 3 <= bytes.size &&
      bytes[index] == ZERO &&
      bytes[index + 1] == ZERO &&
      bytes[index + 2] == ONE
    ) {
      return 3
    }
    if (index + 4 <= bytes.size &&
      bytes[index] == ZERO &&
      bytes[index + 1] == ZERO &&
      bytes[index + 2] == ZERO &&
      bytes[index + 3] == ONE
    ) {
      return 4
    }
    return null
  }

  private data class StartCode(val index: Int, val length: Int)

  private companion object {
    private const val MAX_BUFFER_BYTES = 2 * 1024 * 1024
    private const val TRAILING_SEARCH_BYTES = 8
    private const val NAL_NON_IDR = 1
    private const val NAL_IDR = 5
    private const val NAL_SPS = 7
    private const val NAL_PPS = 8
    private val ZERO = 0.toByte()
    private val ONE = 1.toByte()
  }
}
