package lv.jolkins.pixelorchestrator.app.phoneautomation

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class RootPowerKeyDevice(
  val path: String,
  val name: String,
  val score: Int
) {
  fun displayLabel(): String = name.ifBlank { path }
}

internal sealed interface RootPowerKeyEvent {
  data class SourceSelected(
    val device: RootPowerKeyDevice
  ) : RootPowerKeyEvent

  data class PowerButtonPressed(
    val observedAtUptimeMillis: Long,
    val device: RootPowerKeyDevice
  ) : RootPowerKeyEvent

  data class FatalError(
    val detail: String
  ) : RootPowerKeyEvent
}

internal interface RootPowerKeyMonitor {
  val events: Flow<RootPowerKeyEvent>

  fun selectedDevice(): RootPowerKeyDevice?
}

internal class AndroidRootPowerKeyMonitor(
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val processFactory: RootTouchProcessFactory = DefaultRootTouchProcessFactory,
  private val uptimeClock: () -> Long = SystemClock::uptimeMillis
) : RootPowerKeyMonitor {
  private val selectedDeviceState = MutableStateFlow<RootPowerKeyDevice?>(null)

  override val events: Flow<RootPowerKeyEvent> = callbackFlow {
    val candidates = discoverPowerKeyDevices()
    if (candidates.isEmpty()) {
      trySend(RootPowerKeyEvent.FatalError("No physical power-button input device could be identified"))
      close()
      return@callbackFlow
    }

    var shuttingDown = false
    var currentProcess: RootTouchProcess? = null

    val monitorJob = launch(ioDispatcher) {
      var lastFailureDetail = ""
      try {
        candidates.forEachIndexed { index, device ->
          if (shuttingDown) {
            return@forEachIndexed
          }

          selectedDeviceState.value = device
          runCatching {
            Log.d(TAG, "root_power_key_source_selected source=${device.displayLabel()}")
          }
          trySend(RootPowerKeyEvent.SourceSelected(device))

          val process = try {
            processFactory.start(buildMonitorCommand(device))
          } catch (error: Throwable) {
            lastFailureDetail = "Could not start power-button monitor for ${device.displayLabel()}: ${error.message ?: error::class.java.simpleName}"
            return@forEachIndexed
          }
          currentProcess = process

          try {
            lastFailureDetail = monitorDevice(process) { observedAtUptimeMillis ->
              trySend(
                RootPowerKeyEvent.PowerButtonPressed(
                  observedAtUptimeMillis = observedAtUptimeMillis,
                  device = device
                )
              )
            }
          } finally {
            currentProcess = null
            process.destroy()
          }

          if (shuttingDown) {
            return@forEachIndexed
          }
          if (index < candidates.lastIndex) {
            selectedDeviceState.value = null
          }
        }
      } finally {
        selectedDeviceState.value = null
      }

      if (!shuttingDown) {
        val detail = lastFailureDetail.ifBlank {
          "All physical power-button input sources failed"
        }
        trySend(RootPowerKeyEvent.FatalError(detail))
        close()
      }
    }

    awaitClose {
      shuttingDown = true
      currentProcess?.destroy()
      monitorJob.cancel()
      selectedDeviceState.value = null
    }
  }

  override fun selectedDevice(): RootPowerKeyDevice? = selectedDeviceState.value

  private suspend fun monitorDevice(
    process: RootTouchProcess,
    emitPowerButtonPressed: (Long) -> Unit
  ): String = coroutineScope {
    var stderrText = ""
    val stderrJob = launch(ioDispatcher) {
      stderrText = process.stderr.readText().trim()
    }
    try {
      process.stdout.useLines { lines ->
        lines.forEach { line ->
          val parsed = RootTouchLineParser.parse(line) ?: return@forEach
          if (parsed.type == "EV_KEY" && parsed.code == "KEY_POWER" && parsed.value.equals("DOWN", ignoreCase = true)) {
            emitPowerButtonPressed(uptimeClock())
          }
        }
      }
      val exitCode = process.waitFor()
      return@coroutineScope stderrText.ifBlank {
        "The root power-button monitor exited with code $exitCode"
      }
    } finally {
      stderrJob.cancelAndJoin()
    }
  }

  private suspend fun discoverPowerKeyDevices(): List<RootPowerKeyDevice> {
    val result = runRootCommand("getevent -lp")
    if (result.exitCode != 0) {
      return emptyList()
    }
    return RootPowerKeyDeviceDiscovery.parsePowerKeyDevices(result.stdout)
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

  private fun buildMonitorCommand(device: RootPowerKeyDevice): String {
    return "exec getevent -lt ${singleQuote(device.path)}"
  }

  private fun singleQuote(value: String): String {
    return "'${value.replace("'", "'\"'\"'")}'"
  }

  companion object {
    private const val ROOT_COMMAND_TIMEOUT_MILLIS = 5_000L
    private const val TAG = "RootPowerKeyMonitor"
  }
}

internal object RootPowerKeyDeviceDiscovery {
  fun parsePowerKeyDevices(output: String): List<RootPowerKeyDevice> {
    val candidates = mutableListOf<MutablePowerKeyDeviceCandidate>()
    var current: MutablePowerKeyDeviceCandidate? = null
    output.lineSequence().forEach { rawLine ->
      val line = rawLine.trim()
      when {
        rawLine.startsWith("add device") -> {
          current?.takeIf { it.hasPowerKey }?.let(candidates::add)
          current = MutablePowerKeyDeviceCandidate(
            path = rawLine.substringAfter(":").trim()
          )
        }

        current == null -> Unit
        line.startsWith("name:") -> {
          current?.name = line.substringAfter("name:").trim().trim('"')
        }

        line.contains("KEY_POWER") -> {
          current?.hasPowerKey = true
        }
      }
    }
    current?.takeIf { it.hasPowerKey }?.let(candidates::add)

    return candidates
      .sortedByDescending { it.score() }
      .map { candidate ->
        RootPowerKeyDevice(
          path = candidate.path,
          name = candidate.name,
          score = candidate.score()
        )
      }
  }

  private data class MutablePowerKeyDeviceCandidate(
    val path: String,
    var name: String = "",
    var hasPowerKey: Boolean = false
  ) {
    fun score(): Int {
      var score = 1
      val loweredName = name.lowercase()
      if (loweredName == PIXEL_GPIO_KEYS_NAME || loweredName == "gpio-keys") {
        score += PIXEL_GPIO_KEYS_BONUS
      }
      if (loweredName.contains("gpio")) {
        score += 10
      }
      if (loweredName.contains("key")) {
        score += 5
      }
      if (loweredName.contains("fingerprint")) {
        score -= 5
      }
      return score
    }
  }

  private const val PIXEL_GPIO_KEYS_NAME = "gpio_keys"
  private const val PIXEL_GPIO_KEYS_BONUS = 100
}
