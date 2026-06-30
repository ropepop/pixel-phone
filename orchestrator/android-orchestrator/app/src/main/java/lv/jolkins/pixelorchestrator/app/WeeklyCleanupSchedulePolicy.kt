package lv.jolkins.pixelorchestrator.app

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

internal object WeeklyCleanupSchedulePolicy {
  val TARGET_DAY_OF_WEEK: DayOfWeek = DayOfWeek.MONDAY
  const val TARGET_HOUR = 3
  const val TARGET_MINUTE = 0

  fun nextRunAfter(
    now: ZonedDateTime,
    dayOfWeek: DayOfWeek = TARGET_DAY_OF_WEEK,
    hour: Int = TARGET_HOUR,
    minute: Int = TARGET_MINUTE
  ): ZonedDateTime {
    var candidate = now
      .toLocalDate()
      .with(TemporalAdjusters.nextOrSame(dayOfWeek))
      .atTime(hour, minute)
      .atZone(now.zone)
    if (!candidate.isAfter(now)) {
      candidate = candidate.toLocalDate().plusWeeks(1).atTime(hour, minute).atZone(now.zone)
    }
    return candidate
  }

  fun nextRunAfter(
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    dayOfWeek: DayOfWeek = TARGET_DAY_OF_WEEK,
    hour: Int = TARGET_HOUR,
    minute: Int = TARGET_MINUTE
  ): Long {
    val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zoneId)
    return nextRunAfter(now, dayOfWeek, hour, minute).toInstant().toEpochMilli()
  }
}
