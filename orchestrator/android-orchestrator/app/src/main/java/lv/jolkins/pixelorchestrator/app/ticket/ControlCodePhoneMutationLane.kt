package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes every ViVi/RS phone mutation, including scheduled cleanup work. */
internal class ControlCodePhoneMutationLane {
  private val mutex = Mutex()

  suspend fun <T> withOwnership(block: suspend () -> T): T = mutex.withLock {
    block()
  }
}
