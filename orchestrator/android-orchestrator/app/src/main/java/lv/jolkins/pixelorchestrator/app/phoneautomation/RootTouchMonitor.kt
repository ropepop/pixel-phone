package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class RootTouchDevice(
  val path: String,
  val name: String,
  val score: Int
) {
  fun displayLabel(): String = name.ifBlank { path }
}

internal data class RootTouchSnapshot(
  val activeTouchCount: Int = 0,
  val btnTouchActive: Boolean = false,
  val toolFingerActive: Boolean = false,
  val activeSlotCount: Int = 0,
  val selectedDevice: RootTouchDevice? = null,
  val lastEventUptimeMillis: Long = 0L
) {
  fun isRawTouchActive(): Boolean = btnTouchActive || toolFingerActive || activeSlotCount > 0
}

internal sealed interface RootTouchEvent {
  data class TouchCountChanged(
    val activeTouchCount: Int,
    val observedAtUptimeMillis: Long,
    val snapshot: RootTouchSnapshot = RootTouchSnapshot(
      activeTouchCount = activeTouchCount,
      lastEventUptimeMillis = observedAtUptimeMillis
    )
  ) : RootTouchEvent

  data class SourceSelected(
    val device: RootTouchDevice
  ) : RootTouchEvent

  data class FatalError(
    val detail: String
  ) : RootTouchEvent
}

internal interface RootTouchMonitor {
  val events: Flow<RootTouchEvent>

  fun activeTouchCount(): Int

  fun selectedDevice(): RootTouchDevice?

  fun currentSnapshot(): RootTouchSnapshot

  fun resetActiveTouchCount()
}

internal class AndroidRootTouchMonitor(
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val processFactory: RootTouchProcessFactory = DefaultRootTouchProcessFactory,
  private val uptimeClock: () -> Long = SystemClock::uptimeMillis
) : RootTouchMonitor {
  private val activeTouchCountState = MutableStateFlow(0)
  private val selectedDeviceState = MutableStateFlow<RootTouchDevice?>(null)
  private val currentSnapshotState = MutableStateFlow(RootTouchSnapshot())
  private val resetRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

  override val events: Flow<RootTouchEvent> = callbackFlow {
    val candidates = discoverTouchDevices()
    if (candidates.isEmpty()) {
      trySend(RootTouchEvent.FatalError("No touchscreen input device could be identified"))
      close()
      return@callbackFlow
    }

    var shuttingDown = false
    var currentProcess: RootTouchProcess? = null
    var zeroConfirmationJob: Job? = null
    val tracker = RootTouchStateTracker()

    fun syncSnapshot(confirmedCount: Int, observedAtUptimeMillis: Long) {
      currentSnapshotState.value = RootTouchSnapshot(
        activeTouchCount = confirmedCount,
        btnTouchActive = tracker.isTouchButtonActive(),
        toolFingerActive = tracker.isToolFingerActive(),
        activeSlotCount = tracker.activeSlotCount(),
        selectedDevice = selectedDeviceState.value,
        lastEventUptimeMillis = observedAtUptimeMillis
      )
    }

    suspend fun emitTouchCountIfChanged(newCount: Int, observedAtUptimeMillis: Long) {
      syncSnapshot(newCount, observedAtUptimeMillis)
      if (newCount == activeTouchCountState.value) {
        return
      }
      activeTouchCountState.value = newCount
      runCatching {
        Log.d(
          TAG,
          "root_touch_count_changed count=$newCount btn=${currentSnapshotState.value.btnTouchActive} tool=${currentSnapshotState.value.toolFingerActive} slots=${currentSnapshotState.value.activeSlotCount} source=${currentSnapshotState.value.selectedDevice?.displayLabel() ?: "unknown"}"
        )
      }
      trySend(
        RootTouchEvent.TouchCountChanged(
          activeTouchCount = newCount,
          observedAtUptimeMillis = observedAtUptimeMillis,
          snapshot = currentSnapshotState.value
        )
      )
    }

    fun cancelZeroConfirmation() {
      if (zeroConfirmationJob != null) {
        runCatching {
          Log.d(TAG, "root_touch_zero_confirmation_cancelled")
        }
      }
      zeroConfirmationJob?.cancel()
      zeroConfirmationJob = null
    }

    fun scheduleZeroConfirmation(observedAtUptimeMillis: Long) {
      cancelZeroConfirmation()
      runCatching {
        Log.d(TAG, "root_touch_zero_confirmation_started")
      }
      zeroConfirmationJob = launch {
        delay(TOUCH_RELEASE_CONFIRMATION_MILLIS)
        if (tracker.rawActiveTouchCount() == 0) {
          runCatching {
            Log.d(TAG, "root_touch_zero_confirmation_committed")
          }
          emitTouchCountIfChanged(newCount = 0, observedAtUptimeMillis = observedAtUptimeMillis)
        }
      }
    }

    val resetJob = launch {
      resetRequests.collect {
        cancelZeroConfirmation()
        tracker.reset()
        emitTouchCountIfChanged(newCount = 0, observedAtUptimeMillis = uptimeClock())
      }
    }

    val monitorJob = launch(ioDispatcher) {
      var lastFailureDetail = ""
      try {
        candidates.forEachIndexed { index, device ->
          if (shuttingDown) {
            return@forEachIndexed
          }

          tracker.reset()
          cancelZeroConfirmation()
          emitTouchCountIfChanged(newCount = 0, observedAtUptimeMillis = uptimeClock())
          selectedDeviceState.value = device
          syncSnapshot(
            confirmedCount = activeTouchCountState.value,
            observedAtUptimeMillis = currentSnapshotState.value.lastEventUptimeMillis
          )
          runCatching {
            Log.d(TAG, "root_touch_source_selected source=${device.displayLabel()}")
          }
          trySend(RootTouchEvent.SourceSelected(device))

          val process = try {
            processFactory.start(buildMonitorCommand(device))
          } catch (error: Throwable) {
            lastFailureDetail = "Could not start touch monitor for ${device.displayLabel()}: ${error.message ?: error::class.java.simpleName}"
            return@forEachIndexed
          }
          currentProcess = process

          try {
            lastFailureDetail = monitorDevice(
              process = process,
              tracker = tracker,
              emitTouchCountIfChanged = ::emitTouchCountIfChanged,
              syncSnapshot = ::syncSnapshot,
              currentConfirmedTouchCount = { activeTouchCountState.value },
              cancelZeroConfirmation = ::cancelZeroConfirmation,
              scheduleZeroConfirmation = ::scheduleZeroConfirmation
            )
          } finally {
            currentProcess = null
            process.destroy()
          }

          if (shuttingDown) {
            return@forEachIndexed
          }
          if (index < candidates.lastIndex) {
            tracker.reset()
            cancelZeroConfirmation()
            emitTouchCountIfChanged(newCount = 0, observedAtUptimeMillis = uptimeClock())
          }
        }
      } finally {
        selectedDeviceState.value = null
        currentSnapshotState.value = currentSnapshotState.value.copy(selectedDevice = null)
      }

      if (!shuttingDown) {
        tracker.reset()
        cancelZeroConfirmation()
        emitTouchCountIfChanged(newCount = 0, observedAtUptimeMillis = uptimeClock())
        val detail = lastFailureDetail.ifBlank {
          "All touchscreen input sources failed"
        }
        trySend(RootTouchEvent.FatalError(detail))
        close()
      }
    }

    awaitClose {
      shuttingDown = true
      resetJob.cancel()
      cancelZeroConfirmation()
      currentProcess?.destroy()
      monitorJob.cancel()
      activeTouchCountState.value = 0
      selectedDeviceState.value = null
      currentSnapshotState.value = RootTouchSnapshot()
    }
  }

  override fun activeTouchCount(): Int = activeTouchCountState.value

  override fun selectedDevice(): RootTouchDevice? = selectedDeviceState.value

  override fun currentSnapshot(): RootTouchSnapshot = currentSnapshotState.value

  override fun resetActiveTouchCount() {
    activeTouchCountState.value = 0
    currentSnapshotState.value = currentSnapshotState.value.copy(
      activeTouchCount = 0,
      btnTouchActive = false,
      toolFingerActive = false,
      activeSlotCount = 0,
      lastEventUptimeMillis = uptimeClock()
    )
    resetRequests.tryEmit(Unit)
  }

  private suspend fun monitorDevice(
    process: RootTouchProcess,
    tracker: RootTouchStateTracker,
    emitTouchCountIfChanged: suspend (Int, Long) -> Unit,
    syncSnapshot: (Int, Long) -> Unit,
    currentConfirmedTouchCount: () -> Int,
    cancelZeroConfirmation: () -> Unit,
    scheduleZeroConfirmation: (Long) -> Unit
  ): String = coroutineScope {
    var stderrText = ""
    val stderrJob = launch(ioDispatcher) {
      stderrText = process.stderr.readText().trim()
    }
    try {
      process.stdout.useLines { lines ->
        lines.forEach { line ->
          tracker.consumeLine(line) ?: return@forEach
          val observedAtUptimeMillis = uptimeClock()
          val rawCount = tracker.rawActiveTouchCount()
          if (rawCount > 0) {
            cancelZeroConfirmation()
            emitTouchCountIfChanged(rawCount, observedAtUptimeMillis)
          } else {
            syncSnapshot(currentConfirmedTouchCount(), observedAtUptimeMillis)
            scheduleZeroConfirmation(observedAtUptimeMillis)
          }
        }
      }
      val exitCode = process.waitFor()
      return@coroutineScope stderrText.ifBlank {
        "The root touch monitor exited with code $exitCode"
      }
    } finally {
      stderrJob.cancelAndJoin()
    }
  }

  private suspend fun discoverTouchDevices(): List<RootTouchDevice> {
    val result = runRootCommand("getevent -lp")
    if (result.exitCode != 0) {
      return emptyList()
    }
    return RootTouchDeviceDiscovery.parseTouchDevices(result.stdout)
  }

  private suspend fun runRootCommand(command: String): RootTouchProcessResult {
    return withContext(ioDispatcher) {
      withTimeout(ROOT_COMMAND_TIMEOUT_MILLIS) {
        val process = processFactory.start(command)
        try {
          val stdout = process.stdout.readText()
          val stderr = process.stderr.readText()
          RootTouchProcessResult(
            exitCode = process.waitFor(),
            stdout = stdout,
            stderr = stderr
          )
        } finally {
          process.destroy()
        }
      }
    }
  }

  private fun buildMonitorCommand(device: RootTouchDevice): String {
    return "exec getevent -lt ${singleQuote(device.path)}"
  }

  private fun singleQuote(value: String): String {
    return "'${value.replace("'", "'\"'\"'")}'"
  }

  companion object {
    private const val ROOT_COMMAND_TIMEOUT_MILLIS = 5_000L
    private const val TOUCH_RELEASE_CONFIRMATION_MILLIS = 180L
    private const val TAG = "RootTouchMonitor"
  }
}

internal object RootTouchDeviceDiscovery {
  fun parseTouchDevices(output: String): List<RootTouchDevice> {
    val candidates = mutableListOf<MutableTouchDeviceCandidate>()
    var current: MutableTouchDeviceCandidate? = null
    output.lineSequence().forEach { rawLine ->
      val line = rawLine.trim()
      when {
        rawLine.startsWith("add device") -> {
          current?.takeIf { it.isTouchCapable() }?.let(candidates::add)
          current = MutableTouchDeviceCandidate(
            path = rawLine.substringAfter(":").trim()
          )
        }

        current == null -> Unit
        line.startsWith("name:") -> {
          current?.name = line.substringAfter("name:").trim().trim('"')
        }

        line.contains("INPUT_PROP_DIRECT") -> {
          current?.hasDirectInput = true
        }

        line.contains("ABS_MT_POSITION_X") || line.contains("ABS_MT_POSITION_Y") -> {
          current?.hasMultiTouchPosition = true
        }

        line.contains("ABS_MT_SLOT") -> {
          current?.hasMultiTouchSlot = true
        }

        line.contains("BTN_TOUCH") -> {
          current?.hasTouchButton = true
        }

        line.contains("BTN_TOOL_FINGER") -> {
          current?.hasToolFinger = true
        }
      }
    }
    current?.takeIf { it.isTouchCapable() }?.let(candidates::add)

    return candidates
      .sortedByDescending { it.score() }
      .map { candidate ->
        RootTouchDevice(
          path = candidate.path,
          name = candidate.name,
          score = candidate.score()
        )
      }
  }

  private data class MutableTouchDeviceCandidate(
    val path: String,
    var name: String = "",
    var hasTouchButton: Boolean = false,
    var hasToolFinger: Boolean = false,
    var hasMultiTouchPosition: Boolean = false,
    var hasMultiTouchSlot: Boolean = false,
    var hasDirectInput: Boolean = false
  ) {
    fun isTouchCapable(): Boolean {
      return hasTouchButton || hasMultiTouchPosition
    }

    fun score(): Int {
      var score = 0
      if (name == PIXEL_TOUCHSCREEN_NAME) {
        score += PIXEL_TOUCHSCREEN_BONUS
      }
      if (hasMultiTouchPosition) {
        score += 4
      }
      if (hasTouchButton) {
        score += 2
      }
      if (hasToolFinger) {
        score += 1
      }
      if (hasMultiTouchSlot) {
        score += 1
      }
      if (hasDirectInput) {
        score += 1
      }
      val loweredName = name.lowercase()
      if (loweredName.contains("touch") || loweredName.contains("screen") || loweredName.contains("panel")) {
        score += 1
      }
      return score
    }
  }

  private const val PIXEL_TOUCHSCREEN_NAME = "synaptics_tcm_touch"
  private const val PIXEL_TOUCHSCREEN_BONUS = 100
}

internal class RootTouchStateTracker {
  private val activeSlots = mutableSetOf<Int>()
  private var currentSlot = 0
  private var touchButtonActive = false
  private var toolFingerActive = false
  private var usesMultiTouchSlots = false
  private var lastEmittedCount = 0

  fun consumeLine(line: String): Int? {
    val parsed = RootTouchLineParser.parse(line) ?: return null
    val changed = when {
      parsed.type == "EV_ABS" && parsed.code == "ABS_MT_SLOT" -> {
        currentSlot = parseNumber(parsed.value) ?: currentSlot
        false
      }

      parsed.type == "EV_ABS" && parsed.code == "ABS_MT_TRACKING_ID" -> {
        usesMultiTouchSlots = true
        if (isReleasedTrackingId(parsed.value)) {
          activeSlots.remove(currentSlot)
        } else {
          activeSlots.add(currentSlot)
        }
      }

      parsed.type == "EV_KEY" && parsed.code == "BTN_TOUCH" -> {
        val nextValue = when (parsed.value.uppercase()) {
          "DOWN" -> true
          "UP" -> false
          else -> parseNumber(parsed.value)?.let { it != 0 } ?: touchButtonActive
        }
        if (nextValue == touchButtonActive) {
          false
        } else {
          touchButtonActive = nextValue
          true
        }
      }

      parsed.type == "EV_KEY" && parsed.code == "BTN_TOOL_FINGER" -> {
        val nextValue = when (parsed.value.uppercase()) {
          "DOWN" -> true
          "UP" -> false
          else -> parseNumber(parsed.value)?.let { it != 0 } ?: toolFingerActive
        }
        if (nextValue == toolFingerActive) {
          false
        } else {
          toolFingerActive = nextValue
          true
        }
      }

      else -> false
    }
    if (!changed) {
      return null
    }

    val newCount = rawActiveTouchCount()
    if (newCount == lastEmittedCount) {
      return null
    }
    lastEmittedCount = newCount
    return newCount
  }

  fun rawActiveTouchCount(): Int {
    return when {
      usesMultiTouchSlots || activeSlots.isNotEmpty() -> activeSlots.size
      touchButtonActive || toolFingerActive -> 1
      else -> 0
    }
  }

  fun activeSlotCount(): Int = activeSlots.size

  fun isTouchButtonActive(): Boolean = touchButtonActive

  fun isToolFingerActive(): Boolean = toolFingerActive

  fun reset() {
    activeSlots.clear()
    currentSlot = 0
    touchButtonActive = false
    toolFingerActive = false
    usesMultiTouchSlots = false
    lastEmittedCount = 0
  }

  private fun isReleasedTrackingId(raw: String): Boolean {
    val value = raw.trim().lowercase()
    return value == "ffffffff" || value == "-1"
  }
}

internal object RootTouchLineParser {
  private val linePattern = Regex("""^\[\s*[^]]+\]\s+(?:(\S+):\s+)?(\S+)\s+(\S+)\s+(\S+).*$""")

  fun parse(line: String): ParsedRootTouchLine? {
    val match = linePattern.matchEntire(sanitize(line)) ?: return null
    return ParsedRootTouchLine(
      path = match.groupValues[1],
      type = match.groupValues[2],
      code = match.groupValues[3],
      value = match.groupValues[4]
    )
  }

  private fun sanitize(line: String): String = line.replace("\u0000", "").trim()
}

internal data class ParsedRootTouchLine(
  val path: String,
  val type: String,
  val code: String,
  val value: String
)

internal interface RootTouchProcessFactory {
  fun start(command: String): RootTouchProcess
}

internal interface RootTouchProcess {
  val stdout: BufferedReader
  val stderr: BufferedReader

  fun waitFor(): Int

  fun destroy()
}

internal object DefaultRootTouchProcessFactory : RootTouchProcessFactory {
  override fun start(command: String): RootTouchProcess {
    val process = ProcessBuilder("su", "-c", command)
      .redirectErrorStream(false)
      .start()
    return ProcessBuilderRootTouchProcess(process)
  }
}

internal class ProcessBuilderRootTouchProcess(
  private val process: Process
) : RootTouchProcess {
  override val stdout: BufferedReader = process.inputStream.bufferedReader()
  override val stderr: BufferedReader = process.errorStream.bufferedReader()

  override fun waitFor(): Int = process.waitFor()

  override fun destroy() {
    process.destroy()
    if (process.isAlive) {
      process.destroyForcibly()
    }
  }
}

internal data class RootTouchProcessResult(
  val exitCode: Int,
  val stdout: String,
  val stderr: String
)

private fun parseNumber(raw: String): Int? {
  val value = raw.trim()
  return when {
    value.equals("DOWN", ignoreCase = true) -> 1
    value.equals("UP", ignoreCase = true) -> 0
    value.startsWith("-") -> value.toIntOrNull()
    value.matches(Regex("""[0-9]+""")) -> value.toIntOrNull()
    value.matches(Regex("""[0-9a-fA-F]+""")) -> value.toInt(16)
    else -> null
  }
}
