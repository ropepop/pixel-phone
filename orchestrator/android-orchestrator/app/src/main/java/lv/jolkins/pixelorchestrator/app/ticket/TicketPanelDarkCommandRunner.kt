package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import lv.jolkins.pixelorchestrator.rootexec.RootResult

/** Runs inside the existing phone mutation lane; borrowed action leases stay with their owner. */
internal class TicketPanelDarkCommandRunner(
  private val currentLease: () -> TicketActionPanelDarkLease?,
  private val createLease: (String) -> TicketActionPanelDarkLease,
  private val onOwnedLeaseChanged: (TicketActionPanelDarkLease?) -> Unit
) {
  suspend fun run(reason: String, command: suspend () -> RootResult): RootResult {
    currentCoroutineContext().ensureActive()
    val borrowed = currentLease()
    val lease = borrowed ?: createLease(reason)
    val owned = borrowed == null
    var acquired = !owned
    var finalizationSafe = !owned
    var mutationMayHaveDispatched = false
    var result: RootResult
    try {
      if (owned) {
        onOwnedLeaseChanged(lease)
        acquired = lease.acquire()
      }
      result = if (!acquired || !lease.beforeMutationAllowed()) {
        blocked()
      } else {
        coroutineScope {
          currentCoroutineContext().ensureActive()
          mutationMayHaveDispatched = true
          lease.markMutationMayHaveDispatched()
          val work = async { command() }
          while (!work.isCompleted) {
            if (!lease.ongoingCommandAllowed()) {
              work.cancelAndJoin()
              return@coroutineScope blocked()
            }
            delay(TOUCH_POLL_MILLIS)
          }
          val completed = work.await()
          if (lease.beforeMutationAllowed()) completed else blocked()
        }
      }
    } finally {
      // The last effect of a multi-input script can occur immediately before it exits. Its
      // children have now quiesced, including on cancellation; start convergence from that bound.
      if (mutationMayHaveDispatched) lease.markMutationMayHaveDispatched()
      if (owned) {
        withContext(NonCancellable) {
          try {
            finalizationSafe = if (acquired) {
              lease.releaseAfterFinalConvergence("standalone_command_terminal").safe
            } else {
              lease.release("standalone_command_acquire_failed").safe
            }
          } finally {
            if (currentLease() === lease) onOwnedLeaseChanged(null)
          }
        }
      }
    }
    return if (finalizationSafe) result else blocked()
  }

  private fun blocked() = RootResult(
    exitCode = 46,
    stdout = "",
    stderr = "panel-dark command authority unavailable",
    command = "panel_dark_gate",
    durationMs = 0L
  )

  private companion object {
    const val TOUCH_POLL_MILLIS = 5L
  }
}
