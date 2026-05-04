package lv.jolkins.pixelorchestrator.app.ticket

import android.os.SystemClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val now = SystemClock.elapsedRealtime()
    if (!forceFresh) {
      lastRootDump?.let { cached ->
        if (now - lastRootDumpAtMillis in 0..minIntervalMillis) {
          return cached
        }
      }
    }
    return rootDumpMutex.withLock {
      val lockedNow = SystemClock.elapsedRealtime()
      if (!forceFresh) {
        lastRootDump?.let { cached ->
          if (lockedNow - lastRootDumpAtMillis in 0..minIntervalMillis) {
            return@withLock cached
          }
        }
      }
      val result = rootExecutor.runScript(
        """
        uiautomator dump /sdcard/pixel-ticket-window.xml >/dev/null 2>&1
        cat /sdcard/pixel-ticket-window.xml 2>/dev/null || true
        """.trimIndent(),
        timeoutMillis.milliseconds
      )
      lastRootDumpAtMillis = SystemClock.elapsedRealtime()
      lastRootDump = result
      result
    }
  }

  private const val ROOT_DUMP_MIN_INTERVAL_MILLIS = 350L
  private const val ROOT_DUMP_TIMEOUT_MILLIS = 1_100L
}
