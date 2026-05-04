package lv.jolkins.pixelorchestrator.app.ticket

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

class TicketRootPngCaptureEngine(
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
  @Volatile private var message = "Root PNG capture is idle"
  @Volatile private var width: Int? = null
  @Volatile private var height: Int? = null
  @Volatile private var frames = 0L
  @Volatile private var keyFrames = 0L
  @Volatile private var restarts = 0L
  @Volatile private var suppressedRestartRequests = 0L
  @Volatile private var lastFrameAtMillis = 0L
  @Volatile private var lastKeyFrameAtMillis = 0L
  @Volatile private var lastStartAtMillis = 0L
  @Volatile private var lastRestartAtMillis = 0L
  @Volatile private var lastRestartReason: String? = null
  @Volatile private var burstUntilMillis = 0L
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
    val result = rootExecutor.run("command -v screencap >/dev/null 2>&1", timeout = 5.seconds)
    supported = result.ok
    state = if (supported) state else "unavailable"
    message = if (supported) {
      "Root lossless screencap is available"
    } else {
      "screencap is unavailable to root"
    }
    publish()
    return supported
  }

  fun start(sourceWidth: Int, sourceHeight: Int) {
    width = sourceWidth
    height = sourceHeight
    wanted.set(true)
    requestFreshFrame("capture_start")
    if (job?.isActive == true) {
      publish()
      return
    }
    job = scope.launch(Dispatchers.IO) {
      runCaptureLoop(sourceWidth, sourceHeight)
    }
  }

  fun stop(reason: String = "stopped") {
    wanted.set(false)
    job?.cancel()
    job = null
    stopProcess()
    state = "idle"
    message = "Root PNG capture stopped: $reason"
    publish()
  }

  fun requestFreshFrame(reason: String) {
    val now = SystemClock.elapsedRealtime()
    burstUntilMillis = max(burstUntilMillis, now + ROOT_PNG_BURST_MILLIS)
    frameRequests.incrementAndGet()
    Log.i(TAG, "ticket_root_png_frame_requested reason=$reason")
  }

  fun noteSuppressedRestartRequest(reason: String) {
    suppressedRestartRequests += 1
    Log.i(TAG, "ticket_root_png_restart_suppressed reason=$reason")
    publish()
  }

  suspend fun cleanupStaleProcesses() {
    stopProcess()
  }

  fun snapshot(nowMillis: Long = SystemClock.elapsedRealtime()): TicketRootCaptureHealth {
    return TicketRootCaptureHealth(
      supported = supported,
      active = job?.isActive == true,
      state = state,
      message = message,
      width = width,
      height = height,
      bitrate = null,
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

  private suspend fun runCaptureLoop(sourceWidth: Int, sourceHeight: Int) {
    var observedRequests = frameRequests.get()
    while (wanted.get()) {
      try {
        if (captureOnce(sourceWidth, sourceHeight)) {
          state = "active"
          message = "Root PNG capture is active"
          publish()
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        if (wanted.get()) {
          recordRestart("failure")
          state = "restarting"
          message = "Root PNG capture failed: ${error.message ?: error::class.java.simpleName}"
          Log.w(TAG, "ticket_root_png_capture_failed", error)
          publish()
        }
      }
      val interval = if (SystemClock.elapsedRealtime() < burstUntilMillis) {
        ROOT_PNG_BURST_INTERVAL_MILLIS
      } else {
        ROOT_PNG_STEADY_INTERVAL_MILLIS
      }
      val until = SystemClock.elapsedRealtime() + interval
      while (wanted.get() && SystemClock.elapsedRealtime() < until) {
        val currentRequests = frameRequests.get()
        if (currentRequests != observedRequests) {
          observedRequests = currentRequests
          break
        }
        delay(ROOT_PNG_REQUEST_POLL_MILLIS)
      }
    }
    state = "idle"
    message = "Root PNG capture is idle"
    publish()
  }

  private suspend fun captureOnce(sourceWidth: Int, sourceHeight: Int): Boolean = withContext(Dispatchers.IO) {
    lastStartAtMillis = SystemClock.elapsedRealtime()
    state = "capturing"
    message = "Root PNG capture is reading a fresh frame"
    publish()
    val localProcess = ProcessBuilder("su", "-c", "screencap -p")
      .redirectErrorStream(false)
      .start()
    process = localProcess
    val payload = localProcess.inputStream.use { it.readBytes() }
    val stderr = localProcess.errorStream.bufferedReader().use { it.readText() }.trim()
    val exitCode = withTimeoutOrNull(ROOT_PNG_CAPTURE_TIMEOUT_MILLIS) {
      localProcess.waitFor()
    }
    if (exitCode == null) {
      runCatching { localProcess.destroyForcibly() }
      process = null
      recordRestart("capture_timeout")
      return@withContext false
    }
    process = null
    if (exitCode != 0 || !payload.isPng()) {
      if (stderr.isNotBlank()) {
        Log.w(TAG, "ticket_root_png_capture_stderr $stderr")
      }
      recordRestart("capture_exit_$exitCode")
      false
    } else {
      val now = SystemClock.elapsedRealtime()
      frames += 1
      keyFrames += 1
      lastFrameAtMillis = now
      lastKeyFrameAtMillis = now
      onFrame(
        TicketRootCaptureFrame(
          keyFrame = true,
          timestampUs = SystemClock.elapsedRealtimeNanos() / 1_000L,
          payload = payload,
          width = sourceWidth,
          height = sourceHeight
        )
      )
      true
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

  private fun recordRestart(reason: String) {
    restarts += 1
    lastRestartAtMillis = SystemClock.elapsedRealtime()
    lastRestartReason = reason
    synchronized(restartReasonCounts) {
      restartReasonCounts[reason] = (restartReasonCounts[reason] ?: 0L) + 1L
    }
    Log.i(TAG, "ticket_root_png_capture_restart reason=$reason restarts=$restarts")
  }

  private fun publish() {
    onStateChanged(snapshot())
  }

  private fun ageMillis(timestampMillis: Long, nowMillis: Long): Long? {
    return timestampMillis.takeIf { it > 0L }?.let { (nowMillis - it).coerceAtLeast(0L) }
  }

  private fun ByteArray.isPng(): Boolean {
    return size >= 8 &&
      this[0] == 0x89.toByte() &&
      this[1] == 0x50.toByte() &&
      this[2] == 0x4E.toByte() &&
      this[3] == 0x47.toByte() &&
      this[4] == 0x0D.toByte() &&
      this[5] == 0x0A.toByte() &&
      this[6] == 0x1A.toByte() &&
      this[7] == 0x0A.toByte()
  }

  private companion object {
    private const val TAG = "TicketRootPngCapture"
    private const val ROOT_PNG_BURST_MILLIS = 2_000L
    private const val ROOT_PNG_BURST_INTERVAL_MILLIS = 100L
    private const val ROOT_PNG_STEADY_INTERVAL_MILLIS = 500L
    private const val ROOT_PNG_REQUEST_POLL_MILLIS = 25L
    private const val ROOT_PNG_CAPTURE_TIMEOUT_MILLIS = 2_000L
  }
}
