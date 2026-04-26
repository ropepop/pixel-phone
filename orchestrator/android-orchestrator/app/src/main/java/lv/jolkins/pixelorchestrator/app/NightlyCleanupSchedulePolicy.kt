package lv.jolkins.pixelorchestrator.app

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

internal object NightlyCleanupSchedulePolicy {
  const val TARGET_HOUR = 3
  const val TARGET_MINUTE = 0

  fun nextRunAfter(
    now: ZonedDateTime,
    hour: Int = TARGET_HOUR,
    minute: Int = TARGET_MINUTE
  ): ZonedDateTime {
    var candidate = now.toLocalDate().atTime(hour, minute).atZone(now.zone)
    if (!candidate.isAfter(now)) {
      candidate = now.toLocalDate().plusDays(1).atTime(hour, minute).atZone(now.zone)
    }
    return candidate
  }

  fun nextRunAfter(
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    hour: Int = TARGET_HOUR,
    minute: Int = TARGET_MINUTE
  ): Long {
    val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zoneId)
    return nextRunAfter(now, hour, minute).toInstant().toEpochMilli()
  }
}
