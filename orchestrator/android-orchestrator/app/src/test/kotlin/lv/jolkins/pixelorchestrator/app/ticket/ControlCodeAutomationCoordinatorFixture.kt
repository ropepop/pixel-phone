package lv.jolkins.pixelorchestrator.app.ticket

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

internal enum class ControlCodePrepareScheduleResult {
  STARTED,
  REQUEST_OWNED,
  DEDUPLICATED
}

/**
 * Owns the handoff between speculative background preparation and an operator request.
 * A request claim cancels and joins the preparation job before the caller can touch ViVi.
 */
internal class ControlCodeAutomationCoordinator(
  private val scope: CoroutineScope,
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val beforePrepareStart: () -> Unit = {}
) {
  private val lock = Any()
  private var prepareJob: Job? = null
  private var prepareBlock: (suspend () -> Unit)? = null
  private var prepareResumeAfterClaim: Boolean = false
  private var pendingPrepare: (suspend () -> Unit)? = null
  private val retiringJobs = mutableSetOf<Job>()
  private var requestClaims: Int = 0

  fun schedulePrepare(block: suspend () -> Unit): ControlCodePrepareScheduleResult {
    lateinit var scheduledJob: Job
    synchronized(lock) {
      if (requestClaims > 0) {
        if (pendingPrepare == null) {
          pendingPrepare = block
        }
        return ControlCodePrepareScheduleResult.REQUEST_OWNED
      }
      if (prepareJob != null) {
        return ControlCodePrepareScheduleResult.DEDUPLICATED
      }
      scheduledJob = createPrepareJobLocked(block, resumeAfterClaim = false)
    }
    beforePrepareStart()
    scheduledJob.start()
    return ControlCodePrepareScheduleResult.STARTED
  }

  suspend fun claimRequest() {
    val preparation = synchronized(lock) {
      requestClaims += 1
      prepareJob.also { job ->
        if (job != null && prepareResumeAfterClaim && pendingPrepare == null) {
          pendingPrepare = prepareBlock
        }
        if (job != null) {
          retiringJobs.add(job)
        }
        prepareJob = null
        prepareBlock = null
        prepareResumeAfterClaim = false
      }
    }
    try {
      preparation?.cancelAndJoin()
    } finally {
      if (preparation != null) {
        synchronized(lock) { retiringJobs.remove(preparation) }
      }
    }
  }

  fun releaseRequest() {
    var scheduledJob: Job? = null
    synchronized(lock) {
      requestClaims = (requestClaims - 1).coerceAtLeast(0)
      if (requestClaims == 0 && prepareJob?.isActive != true) {
        pendingPrepare?.let { block ->
          pendingPrepare = null
          scheduledJob = createPrepareJobLocked(block, resumeAfterClaim = true)
        }
      }
    }
    scheduledJob?.start()
  }

  fun requestClaimed(): Boolean = synchronized(lock) {
    requestClaims > 0
  }

  fun cancelPreparation() {
    val preparation = synchronized(lock) {
      pendingPrepare = null
      prepareJob.also {
        if (it != null) {
          retiringJobs.add(it)
        }
        prepareJob = null
        prepareBlock = null
        prepareResumeAfterClaim = false
      }
    }
    preparation?.cancel()
  }

  suspend fun cancelPreparationAndJoin() {
    val preparations = synchronized(lock) {
      pendingPrepare = null
      prepareJob?.let { retiringJobs.add(it) }
      prepareJob = null
      prepareBlock = null
      prepareResumeAfterClaim = false
      retiringJobs.toList()
    }
    preparations.forEach { it.cancel() }
    try {
      preparations.joinAll()
    } finally {
      synchronized(lock) {
        preparations.forEach { retiringJobs.remove(it) }
      }
    }
  }

  private fun createPrepareJobLocked(
    block: suspend () -> Unit,
    resumeAfterClaim: Boolean
  ): Job {
    lateinit var scheduledJob: Job
    scheduledJob = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
      block()
    }
    prepareJob = scheduledJob
    prepareBlock = block
    prepareResumeAfterClaim = resumeAfterClaim
    scheduledJob.invokeOnCompletion {
      var followUp: Job? = null
      synchronized(lock) {
        retiringJobs.remove(scheduledJob)
        if (prepareJob === scheduledJob) {
          prepareJob = null
          prepareBlock = null
          prepareResumeAfterClaim = false
        }
        if (requestClaims == 0 && prepareJob == null) {
          pendingPrepare?.let { pending ->
            pendingPrepare = null
            followUp = createPrepareJobLocked(pending, resumeAfterClaim = true)
          }
        }
      }
      followUp?.start()
    }
    return scheduledJob
  }
}
