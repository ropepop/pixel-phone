package lv.jolkins.pixelorchestrator.app.ticket

/**
 * Keeps canonical Ticket desired-state reads off the 75 ms command hot path. New connections and
 * hot-lane entries reconcile immediately; a negative hot-edge value gets only bounded delayed
 * follow-ups so an asynchronous relay write cannot remain stale for the full live session.
 */
internal class TicketSpacetimeDesiredRefreshState<T>(
  private val hotNegativeRetryDelayMillis: Long = 1_000L,
  private val maxHotNegativeFollowUpReads: Int = 4
) {
  init {
    require(hotNegativeRetryDelayMillis > 0L)
    require(maxHotNegativeFollowUpReads >= 0)
  }

  var cachedDesired: T? = null
    private set

  var cacheLoaded: Boolean = false
    private set

  private var hotLaneWasActive: Boolean = false
  private var refreshPending: Boolean = true
  private var hotNegativeFollowUpReadsRemaining: Int = 0
  private var nextHotNegativeRefreshAtMillis: Long = Long.MAX_VALUE

  fun onClientConnected() {
    cachedDesired = null
    cacheLoaded = false
    hotLaneWasActive = false
    refreshPending = true
    hotNegativeFollowUpReadsRemaining = 0
    nextHotNegativeRefreshAtMillis = Long.MAX_VALUE
  }

  fun shouldRefresh(hotLaneActive: Boolean, hasPendingCommands: Boolean, nowMillis: Long): Boolean {
    if (hotLaneActive && !hotLaneWasActive) {
      refreshPending = true
      hotNegativeFollowUpReadsRemaining = maxHotNegativeFollowUpReads
      nextHotNegativeRefreshAtMillis = nowMillis
    } else if (!hotLaneActive && hotLaneWasActive) {
      hotNegativeFollowUpReadsRemaining = 0
      nextHotNegativeRefreshAtMillis = Long.MAX_VALUE
    }
    hotLaneWasActive = hotLaneActive
    if (hasPendingCommands) {
      return false
    }
    return refreshPending || (
      hotLaneActive &&
        nextHotNegativeRefreshAtMillis != Long.MAX_VALUE &&
        nowMillis >= nextHotNegativeRefreshAtMillis
      )
  }

  fun markRefreshed(
    desired: T?,
    hotLaneActive: Boolean,
    desiredActive: Boolean,
    nowMillis: Long
  ) {
    cachedDesired = desired
    cacheLoaded = true
    refreshPending = false
    if (hotLaneActive && !desiredActive && hotNegativeFollowUpReadsRemaining > 0) {
      hotNegativeFollowUpReadsRemaining -= 1
      nextHotNegativeRefreshAtMillis = if (nowMillis > Long.MAX_VALUE - hotNegativeRetryDelayMillis) {
        Long.MAX_VALUE
      } else {
        nowMillis + hotNegativeRetryDelayMillis
      }
    } else {
      hotNegativeFollowUpReadsRemaining = 0
      nextHotNegativeRefreshAtMillis = Long.MAX_VALUE
    }
  }
}
