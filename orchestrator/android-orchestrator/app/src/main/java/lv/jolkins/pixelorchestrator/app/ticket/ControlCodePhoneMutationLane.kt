package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes every ViVi/RS phone mutation, including scheduled cleanup work. */
internal class ControlCodePhoneMutationLane {
  private val mutex = Mutex()

  suspend fun <T> withOwnership(block: suspend () -> T): T = mutex.withLock {
    block()
  }

  suspend fun <T> tryWithOwnership(block: suspend () -> T): T? {
    if (!mutex.tryLock()) return null
    return try { block() } finally { mutex.unlock() }
  }
}
