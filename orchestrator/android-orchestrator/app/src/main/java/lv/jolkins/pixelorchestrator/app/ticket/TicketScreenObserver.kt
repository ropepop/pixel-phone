package lv.jolkins.pixelorchestrator.app.ticket

import android.os.SystemClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import lv.jolkins.pixelorchestrator.rootexec.RootResult
import kotlin.time.Duration.Companion.milliseconds

internal object TicketScreenObserver {
  private val rootDumpMutex = Mutex()
  @Volatile private var lastRootDumpAtMillis: Long = 0L
  @Volatile private var lastRootDump: RootResult? = null

  suspend fun dumpViviHierarchy(
    rootExecutor: TicketRootCommandWorker,
    reason: String,
    minIntervalMillis: Long = ROOT_DUMP_MIN_INTERVAL_MILLIS,
    timeoutMillis: Long = ROOT_DUMP_TIMEOUT_MILLIS,
    forceFresh: Boolean = false
  ): RootResult {
    val startedAtMillis = SystemClock.elapsedRealtime()
    val now = SystemClock.elapsedRealtime()
    if (!forceFresh) {
      lastRootDump?.let { cached ->
        if (now - lastRootDumpAtMillis in 0..minIntervalMillis) {
          return cached
        }
      }
    }
    return withTimeoutOrNull(timeoutMillis.milliseconds) {
      rootDumpMutex.withLock {
        val lockedNow = SystemClock.elapsedRealtime()
        if (!forceFresh) {
          lastRootDump?.let { cached ->
            if (lockedNow - lastRootDumpAtMillis in 0..minIntervalMillis) {
              return@withLock cached
            }
          }
        }
        val remainingMillis = remainingTimeoutMillis(startedAtMillis, timeoutMillis)
        val result = rootExecutor.runScript(
          TicketUiautomatorDump.command(
            path = "/sdcard/pixel-ticket-window.xml",
            timeoutMillis = remainingMillis
          ),
          remainingMillis.milliseconds
        )
        lastRootDumpAtMillis = SystemClock.elapsedRealtime()
        lastRootDump = result
        result
      }
    } ?: hierarchyDumpTimeoutResult(startedAtMillis, timeoutMillis)
  }

  private fun remainingTimeoutMillis(startedAtMillis: Long, timeoutMillis: Long): Long {
    val elapsedMillis = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
    return (timeoutMillis - elapsedMillis).coerceAtLeast(1L)
  }

  private fun hierarchyDumpTimeoutResult(startedAtMillis: Long, timeoutMillis: Long): RootResult {
    return RootResult(
      exitCode = 124,
      stdout = "",
      stderr = "root hierarchy dump timed out after $timeoutMillis ms",
      command = "uiautomator dump /sdcard/pixel-ticket-window.xml",
      durationMs = (SystemClock.elapsedRealtime() - startedAtMillis).coerceAtLeast(0L)
    )
  }

  private const val ROOT_DUMP_MIN_INTERVAL_MILLIS = 200L
  private const val ROOT_DUMP_TIMEOUT_MILLIS = 1_500L
}
